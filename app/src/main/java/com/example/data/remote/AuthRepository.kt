package com.example.data.remote

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class AuthRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    suspend fun ensureUserInitialized(
        user: FirebaseUser,
        preferredUsername: String? = null,
        preferredDisplayName: String? = null,
        preferredAvatarUrl: String? = null
    ): Result<Unit> {
        val uid = user.uid
        val userDocRef = db.collection("users").document(uid)
        return try {
            val snapshot = userDocRef.get().await()
            if (!snapshot.exists()) {
                val rawDisplay = preferredDisplayName?.takeIf { it.isNotBlank() }
                    ?: user.displayName?.takeIf { it.isNotBlank() }
                    ?: "Usuário Talkback"
                val displayName = rawDisplay.take(50)
                val baseHandle = rawDisplay.lowercase()
                    .replace(Regex("[^a-z0-9_]"), "")
                    .ifBlank { "talkback" }
                    .take(18)
                val username = preferredUsername?.takeIf { it.matches(Regex("^[a-z0-9_.]{3,30}$")) }
                    ?: "${baseHandle}_${uid.take(6).lowercase()}"
                val avatarUrl = (preferredAvatarUrl?.takeIf { it.isNotBlank() }
                    ?: user.photoUrl?.toString())?.take(500)

                val profileMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "username" to username,
                    "displayName" to displayName,
                    "bio" to "Disponível no Talkback",
                    "statusText" to "",
                    "presence" to "online",
                    "primaryColorHex" to 0xFF5865F2L,
                    "secondaryColorHex" to 0xFF4752C4L,
                    "gradientEnabled" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!avatarUrl.isNullOrBlank()) {
                    profileMap["avatarUrl"] = avatarUrl
                }

                val claimMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "username" to username,
                    "displayName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!avatarUrl.isNullOrBlank()) {
                    claimMap["avatarUrl"] = avatarUrl
                }

                val privateMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "blockedUids" to emptyList<String>(),
                    "notifyMessages" to true,
                    "notifyFriendRequests" to true,
                    "notifyInvites" to true,
                    "notifyCalls" to true,
                    "notifyMentions" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val email = user.email?.take(160)
                if (!email.isNullOrBlank()) {
                    privateMap["email"] = email
                }

                val batch = db.batch()
                batch.set(userDocRef, profileMap)
                batch.set(db.collection("usernames").document(username), claimMap)
                batch.set(userDocRef.collection("private").document("account"), privateMap)
                batch.commit().await()
            } else {
                userDocRef.update(
                    mapOf(
                        "presence" to "online",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDocRef.path)
            Result.failure(e)
        }
    }

    fun signOut(
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val uid = auth.currentUser?.uid
                    if (!uid.isNullOrBlank()) {
                        runCatching {
                            db.collection("users").document(uid).update(
                                mapOf(
                                    "presence" to "offline",
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                        }
                    }
                } finally {
                    auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
