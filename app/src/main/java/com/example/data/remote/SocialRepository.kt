package com.example.data.remote

import com.example.data.model.FriendRequest
import com.example.data.model.Friendship
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class SocialRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Usuário precisa estar autenticado.")

    fun observeFriendships(userId: String): Flow<List<Friendship>> = flow {
        val path = "friendships"
        emitAll(
            db.collection(path)
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap -> snap.toObjects(Friendship::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observeFriendRequests(userId: String): Flow<List<FriendRequest>> = flow {
        val path = "friend_requests"
        emitAll(
            db.collection(path)
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(FriendRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.status == "pending" }
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

    suspend fun sendFriendRequestByUsername(targetUsernameRaw: String, senderProfile: UserProfile): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val targetUsername = targetUsernameRaw.trim().lowercase().removePrefix("@")
        if (!targetUsername.matches(Regex("^[a-z0-9_.]{3,30}$"))) {
            return Result.failure(IllegalArgumentException("Nome de usuário inválido."))
        }
        return try {
            val claimSnap = db.collection("usernames").document(targetUsername).get().await()
            if (!claimSnap.exists()) {
                return Result.failure(IllegalStateException("Usuário @$targetUsername não encontrado."))
            }
            val targetUid = claimSnap.getString("userId")
                ?: return Result.failure(IllegalStateException("Cadastro do usuário incompleto."))
            if (targetUid == uid) {
                return Result.failure(IllegalArgumentException("Você não pode adicionar a si mesmo."))
            }

            val friendshipId = listOf(uid, targetUid).sorted().joinToString("_")
            if (db.collection("friendships").document(friendshipId).get().await().exists()) {
                return Result.failure(IllegalStateException("Vocês já são amigos."))
            }

            val targetUser = db.collection("users").document(targetUid).get().await()
                .toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Perfil não encontrado."))

            val reqId = "${uid}_${targetUid}"
            val payload = mutableMapOf<String, Any>(
                "fromUid" to uid,
                "fromUsername" to senderProfile.username,
                "fromDisplayName" to senderProfile.displayName,
                "toUid" to targetUid,
                "toUsername" to targetUser.username,
                "toDisplayName" to targetUser.displayName,
                "status" to "pending",
                "participantUids" to listOf(uid, targetUid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            senderProfile.avatarUrl?.takeIf { it.isNotBlank() }?.let { payload["fromAvatarUrl"] = it }
            targetUser.avatarUrl?.takeIf { it.isNotBlank() }?.let { payload["toAvatarUrl"] = it }

            db.collection("friend_requests").document(reqId).set(payload).await()
            Result.success(targetUser.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "friend_requests")
            Result.failure(e)
        }
    }

    suspend fun acceptFriendRequest(request: FriendRequest): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        if (request.toUid != uid) return Result.failure(IllegalStateException("Apenas o destinatário pode aceitar."))
        return try {
            val friendshipId = listOf(request.fromUid, request.toUid).sorted().joinToString("_")
            val friendshipMap = mutableMapOf<String, Any>(
                "participantUids" to listOf(request.fromUid, request.toUid),
                "userAUid" to request.fromUid,
                "userAName" to request.fromDisplayName,
                "userAUsername" to request.fromUsername,
                "userBUid" to request.toUid,
                "userBName" to request.toDisplayName,
                "userBUsername" to request.toUsername,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            request.fromAvatarUrl?.takeIf { it.isNotBlank() }?.let { friendshipMap["userAAvatarUrl"] = it }
            request.toAvatarUrl?.takeIf { it.isNotBlank() }?.let { friendshipMap["userBAvatarUrl"] = it }

            val batch = db.batch()
            batch.update(
                db.collection("friend_requests").document(request.id),
                mapOf("status" to "accepted", "updatedAt" to FieldValue.serverTimestamp())
            )
            batch.set(db.collection("friendships").document(friendshipId), friendshipMap)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "friendships")
            Result.failure(e)
        }
    }

    suspend fun declineOrCancelRequest(requestId: String): Result<Unit> {
        return try {
            requireUserId()
            db.collection("friend_requests").document(requestId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "friend_requests/$requestId")
            Result.failure(e)
        }
    }

    suspend fun removeFriend(friendshipId: String): Result<Unit> {
        return try {
            requireUserId()
            db.collection("friendships").document(friendshipId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "friendships/$friendshipId")
            Result.failure(e)
        }
    }

    suspend fun blockUser(targetUid: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "users/$uid/private/account"
        return try {
            db.document(path).update(
                mapOf(
                    "blockedUids" to FieldValue.arrayUnion(targetUid),
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

    suspend fun unblockUser(targetUid: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "users/$uid/private/account"
        return try {
            db.document(path).update(
                mapOf(
                    "blockedUids" to FieldValue.arrayRemove(targetUid),
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
}
