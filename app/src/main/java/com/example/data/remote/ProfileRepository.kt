package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.data.model.PresenceStatus
import com.example.data.model.PrivateAccount
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ProfileRepository(
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val auth: FirebaseAuth = Firebase.auth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Usuário precisa estar autenticado com o Google.")

    fun observeProfile(userId: String): Flow<UserProfile?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { it.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid != null) {
                        handleFirestoreError(unwrapped, OperationType.GET, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observePrivateAccount(userId: String): Flow<PrivateAccount?> = flow {
        val path = "users/$userId/private/account"
        emitAll(
            db.collection("users").document(userId)
                .collection("private").document("account")
                .snapshots()
                .map { it.toObject(PrivateAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.GET, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun getUserProfile(userId: String): UserProfile? = try {
        db.collection("users").document(userId).get().await()
            .toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleFirestoreError(e, OperationType.GET, "users/$userId")
        null
    }

    suspend fun updateProfile(
        displayName: String,
        newUsername: String,
        bio: String,
        statusText: String,
        presence: PresenceStatus,
        primaryColorHex: Long,
        secondaryColorHex: Long,
        gradientEnabled: Boolean,
        avatarUrl: String?,
        bannerUrl: String?,
        bannerVideoUrl: String?
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "users/$uid"
        return try {
            val cleanDisplay = displayName.trim()
            val cleanUsername = newUsername.trim().lowercase()
            require(cleanDisplay.length in 1..50) { "Nome de exibição deve ter entre 1 e 50 caracteres." }
            require(cleanUsername.matches(Regex("^[a-z0-9_.]{3,30}$"))) {
                "Nome de usuário deve ter 3-30 caracteres (letras minúsculas, números, _ ou .)."
            }
            require(bio.length <= 300) { "Biografia deve ter no máximo 300 caracteres." }
            require(statusText.length <= 80) { "Status deve ter no máximo 80 caracteres." }

            val userRef = db.collection("users").document(uid)
            val currentSnap = userRef.get().await()
            val oldUsername = currentSnap.getString("username") ?: ""

            val batch = db.batch()
            if (cleanUsername != oldUsername) {
                val newClaimRef = db.collection("usernames").document(cleanUsername)
                val existingClaim = newClaimRef.get().await()
                if (existingClaim.exists() && existingClaim.getString("userId") != uid) {
                    return Result.failure(IllegalStateException("Esse nome de usuário já está em uso."))
                }
                if (oldUsername.isNotBlank()) {
                    batch.delete(db.collection("usernames").document(oldUsername))
                }
                val newClaim = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "username" to cleanUsername,
                    "displayName" to cleanDisplay,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                avatarUrl?.takeIf { it.isNotBlank() }?.let { newClaim["avatarUrl"] = it.take(500) }
                batch.set(newClaimRef, newClaim)
            } else if (oldUsername.isNotBlank()) {
                val claimUpdates = mutableMapOf<String, Any>(
                    "displayName" to cleanDisplay,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                avatarUrl?.takeIf { it.isNotBlank() }?.let { claimUpdates["avatarUrl"] = it.take(500) }
                batch.update(db.collection("usernames").document(oldUsername), claimUpdates)
            }

            val profileUpdates = mutableMapOf<String, Any>(
                "displayName" to cleanDisplay,
                "username" to cleanUsername,
                "bio" to bio.trim(),
                "statusText" to statusText.trim(),
                "presence" to presence.wire,
                "primaryColorHex" to primaryColorHex,
                "secondaryColorHex" to secondaryColorHex,
                "gradientEnabled" to gradientEnabled,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            avatarUrl?.takeIf { it.isNotBlank() }?.let { profileUpdates["avatarUrl"] = it.take(500) }
            bannerUrl?.takeIf { it.isNotBlank() }?.let { profileUpdates["bannerUrl"] = it.take(500) }
            bannerVideoUrl?.takeIf { it.isNotBlank() }?.let { profileUpdates["bannerVideoUrl"] = it.take(500) }

            batch.update(userRef, profileUpdates)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updatePresence(presence: PresenceStatus): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "users/$uid"
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "presence" to presence.wire,
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

    suspend fun updateFcmToken(token: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val path = "users/$uid"
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "fcmToken" to token.take(512),
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

    suspend fun updateNotificationPrefs(
        notifyMessages: Boolean,
        notifyFriendRequests: Boolean,
        notifyInvites: Boolean,
        notifyCalls: Boolean,
        notifyMentions: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "users/$uid/private/account"
        return try {
            db.document(path).update(
                mapOf(
                    "notifyMessages" to notifyMessages,
                    "notifyFriendRequests" to notifyFriendRequests,
                    "notifyInvites" to notifyInvites,
                    "notifyCalls" to notifyCalls,
                    "notifyMentions" to notifyMentions,
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

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        folder: String,
        maxSizeBytes: Long,
        allowedMimePrefixes: List<String>
    ): Result<Pair<String, String>> {
        return try {
            val uid = requireUserId()
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            val allowed = allowedMimePrefixes.any { prefix ->
                mimeType.startsWith(prefix) || prefix == "*/*"
            }
            if (!allowed) {
                return Result.failure(IllegalArgumentException("Tipo de arquivo não suportado ($mimeType)."))
            }

            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val buffer = stream.readBytes()
                if (buffer.size > maxSizeBytes) {
                    val mb = maxSizeBytes / (1024 * 1024)
                    return Result.failure(IllegalArgumentException("Arquivo excede o limite de ${mb}MB."))
                }
                buffer
            } ?: return Result.failure(IllegalStateException("Não foi possível ler o arquivo selecionado."))

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
            val fileName = "${UUID.randomUUID()}.$ext"
            val ref = storage.reference.child("$folder/$uid/$fileName")
            ref.putBytes(bytes).await()
            val downloadUrl = ref.downloadUrl.await().toString()
            Result.success(downloadUrl to fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Falha no upload para o Firebase Storage (${e.localizedMessage ?: "verifique a ativação do Storage no Console Firebase"})."
                )
            )
        }
    }
}
