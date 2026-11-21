package com.example.data.remote

import com.example.data.model.CallSession
import com.example.data.model.CallSignal
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CallRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val serverClock: ServerClock = ServerClock()
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Usuário precisa estar autenticado.")

    fun observeUserCalls(userId: String): Flow<List<CallSession>> = flow {
        val path = "calls"
        serverClock.ensureSynced(db, userId)
        val cutoff = serverClock.queryCutoffAgo(ServerClock.CALL_TTL_MILLIS)
        emitAll(
            db.collection(path)
                .whereArrayContains("participantUids", userId)
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .snapshots()
                .map { snap ->
                    val nowMs = serverClock.nowServerMillis()
                    snap.documents.mapNotNull { doc ->
                        val pending = doc.metadata.hasPendingWrites()
                        val call = doc.toObject(
                            CallSession::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: return@mapNotNull null
                        call.takeIf {
                            !serverClock.isRecordExpired(
                                expiresAt = it.expiresAt,
                                createdAt = it.createdAt,
                                ttlMillis = ServerClock.CALL_TTL_MILLIS,
                                nowMs = nowMs,
                                hasPendingWrites = pending
                            )
                        }
                    }
                }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observeCall(callId: String): Flow<CallSession?> = flow {
        val path = "calls/$callId"
        emitAll(
            db.document(path)
                .snapshots()
                .map { it.toObject(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid != null) {
                        handleFirestoreError(unwrapped, OperationType.GET, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observeSignals(callId: String, userId: String): Flow<List<CallSignal>> = flow {
        val path = "calls/$callId/signals"
        serverClock.ensureSynced(db, userId)
        val cutoff = serverClock.queryCutoffAgo(ServerClock.SIGNAL_TTL_MILLIS)
        emitAll(
            db.collection("calls").document(callId).collection("signals")
                .whereArrayContains("participantUids", userId)
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snap ->
                    val nowMs = serverClock.nowServerMillis()
                    snap.documents.mapNotNull { doc ->
                        val pending = doc.metadata.hasPendingWrites()
                        val signal = doc.toObject(
                            CallSignal::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: return@mapNotNull null
                        signal.takeIf {
                            !serverClock.isRecordExpired(
                                expiresAt = it.expiresAt,
                                createdAt = it.createdAt,
                                ttlMillis = ServerClock.SIGNAL_TTL_MILLIS,
                                nowMs = nowMs,
                                hasPendingWrites = pending
                            )
                        }
                    }
                }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun startCall(
        caller: UserProfile,
        contextType: String,
        contextId: String,
        serverId: String? = null,
        callType: String,
        participantUids: List<String>,
        sfuRoomUrl: String? = null
    ): Result<CallSession> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val callRef = db.collection("calls").document()
        return try {
            serverClock.ensureSynced(db, uid)
            val allParticipants = (participantUids + uid).distinct().take(50)
            val initialStatus = if (contextType == "voice_channel") "active" else "ringing"
            val expiresAt = serverClock.computeExpiresAt(ServerClock.CALL_TTL_MILLIS)
            val payload = mutableMapOf<String, Any>(
                "contextType" to contextType,
                "contextId" to contextId,
                "callerUid" to uid,
                "callerName" to caller.displayName,
                "callType" to callType,
                "status" to initialStatus,
                "participantUids" to allParticipants,
                "activeUids" to listOf(uid),
                "expiresAt" to expiresAt,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            serverId?.takeIf { it.isNotBlank() }?.let { payload["serverId"] = it }
            sfuRoomUrl?.takeIf { it.isNotBlank() }?.let { payload["sfuRoomUrl"] = it.take(500) }
            callRef.set(payload).await()
            val created = CallSession(
                id = callRef.id,
                contextType = contextType,
                contextId = contextId,
                serverId = serverId,
                callerUid = uid,
                callerName = caller.displayName,
                callType = callType,
                status = initialStatus,
                participantUids = allParticipants,
                activeUids = listOf(uid),
                sfuRoomUrl = sfuRoomUrl,
                expiresAt = expiresAt
            )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun acceptCall(callId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "calls/$callId"
        return try {
            db.document(path).update(
                mapOf(
                    "status" to "active",
                    "activeUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun declineCall(callId: String): Result<Unit> {
        val path = "calls/$callId"
        return try {
            val uid = requireUserId()
            db.document(path).update(
                mapOf(
                    "status" to "declined",
                    "activeUids" to emptyList<String>(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            purgeCallSignalsBestEffort(callId, uid)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun endCall(callId: String): Result<Unit> {
        val path = "calls/$callId"
        return try {
            val uid = requireUserId()
            db.document(path).update(
                mapOf(
                    "status" to "ended",
                    "activeUids" to emptyList<String>(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            purgeCallSignalsBestEffort(callId, uid)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private suspend fun purgeCallSignalsBestEffort(callId: String, uid: String) {
        runCatching {
            serverClock.ensureSynced(db, uid)
            val cutoff = serverClock.queryCutoffAgo(ServerClock.SIGNAL_TTL_MILLIS)
            val snap = db.collection("calls").document(callId).collection("signals")
                .whereArrayContains("participantUids", uid)
                .whereGreaterThan("createdAt", cutoff)
                .get()
                .await()
            if (!snap.isEmpty) {
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
    }

    suspend fun updateScreenSharing(callId: String, sharingUid: String?): Result<Unit> {
        val path = "calls/$callId"
        return try {
            requireUserId()
            val payload = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (sharingUid != null) {
                payload["screenSharingUid"] = sharingUid
            } else {
                payload["screenSharingUid"] = FieldValue.delete()
            }
            db.document(path).update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendSignal(
        callId: String,
        participantUids: List<String>,
        toUid: String,
        type: String,
        sdp: String? = null,
        iceCandidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Long? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val sigRef = db.collection("calls").document(callId).collection("signals").document()
        return try {
            serverClock.ensureSynced(db, uid)
            val payload = mutableMapOf<String, Any>(
                "callId" to callId,
                "fromUid" to uid,
                "toUid" to toUid,
                "type" to type,
                "participantUids" to participantUids,
                "expiresAt" to serverClock.computeExpiresAt(ServerClock.SIGNAL_TTL_MILLIS),
                "createdAt" to FieldValue.serverTimestamp()
            )
            sdp?.let { payload["sdp"] = it.take(32000) }
            iceCandidate?.let { payload["iceCandidate"] = it.take(4000) }
            sdpMid?.let { payload["sdpMid"] = it.take(64) }
            sdpMLineIndex?.let { payload["sdpMLineIndex"] = it }
            sigRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, sigRef.path)
            Result.failure(e)
        }
    }
}
