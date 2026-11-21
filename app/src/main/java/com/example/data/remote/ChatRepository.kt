package com.example.data.remote

import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
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

class ChatRepository(
    private val db: FirebaseFirestore,
    private val linkPreviewFetcher: LinkPreviewFetcher = LinkPreviewFetcher(),
    private val auth: FirebaseAuth = Firebase.auth,
    private val serverClock: ServerClock = ServerClock(),
    private val profileRepo: ProfileRepository? = null
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Usuário precisa estar autenticado.")

    fun observeConversations(userId: String): Flow<List<Conversation>> = flow {
        val path = "conversations"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.lastMessageAt?.seconds ?: 0L }
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

    fun observeMessages(conversationId: String, userId: String, pageSize: Long = 50L): Flow<List<ChatMessage>> = flow {
        val path = "conversations/$conversationId/messages"
        serverClock.ensureSynced(db, userId)
        val cutoff = serverClock.queryCutoffThreeHoursAgo()
        emitAll(
            db.collection("conversations").document(conversationId)
                .collection("messages")
                .whereArrayContains("memberUids", userId)
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(pageSize)
                .snapshots()
                .map { snap ->
                    val nowMs = serverClock.nowServerMillis()
                    snap.documents.mapNotNull { doc ->
                        val pending = doc.metadata.hasPendingWrites()
                        val msg = doc.toObject(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?: return@mapNotNull null
                        msg.takeIf { !serverClock.isMessageExpired(it, nowMs = nowMs, hasPendingWrites = pending) }
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

    suspend fun getOrCreateDirectConversation(myProfile: UserProfile, peerUid: String): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val dmId = "dm_${listOf(uid, peerUid).sorted().joinToString("_")}"
        return try {
            val docRef = db.collection("conversations").document(dmId)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "type" to "dm",
                    "ownerUid" to uid,
                    "memberUids" to listOf(uid, peerUid),
                    "lastMessageText" to "",
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "typingUids" to emptyList<String>(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(dmId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "conversations/$dmId")
            Result.failure(e)
        }
    }

    suspend fun createQuickGroup(name: String, memberUids: List<String>, iconUrl: String? = null): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.length > 80) {
            return Result.failure(IllegalArgumentException("Nome do grupo deve ter entre 1 e 80 caracteres."))
        }
        return try {
            val allMembers = (memberUids + uid).distinct().take(50)
            val docRef = db.collection("conversations").document()
            val payload = mutableMapOf<String, Any>(
                "type" to "group",
                "name" to cleanName,
                "ownerUid" to uid,
                "memberUids" to allMembers,
                "lastMessageText" to "Grupo criado",
                "lastMessageSenderUid" to uid,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "typingUids" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            iconUrl?.takeIf { it.isNotBlank() }?.let { payload["iconUrl"] = it.take(500) }
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "conversations")
            Result.failure(e)
        }
    }

    suspend fun updateGroupMembersAndInfo(
        conversationId: String,
        name: String,
        memberUids: List<String>,
        iconUrl: String? = null
    ): Result<Unit> {
        val path = "conversations/$conversationId"
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>(
                "name" to name.trim().take(80),
                "memberUids" to memberUids.distinct().take(50),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            iconUrl?.takeIf { it.isNotBlank() }?.let { updates["iconUrl"] = it.take(500) }
            db.document(path).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        conversation: Conversation,
        sender: UserProfile,
        text: String,
        replyTo: ChatMessage? = null,
        attachmentUrl: String? = null,
        attachmentName: String? = null,
        attachmentType: String? = null,
        attachmentStoragePath: String? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val cleanText = text.trim()
        if (cleanText.isBlank() && attachmentUrl.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Mensagem vazia."))
        }
        val msgRef = db.collection("conversations").document(conversation.id)
            .collection("messages").document()
        return try {
            serverClock.ensureSynced(db, uid)
            val firstUrl = linkPreviewFetcher.extractFirstUrl(cleanText)
            val preview = firstUrl?.let { linkPreviewFetcher.fetchMetadata(it) }

            val payload = mutableMapOf<String, Any>(
                "conversationId" to conversation.id,
                "senderUid" to uid,
                "senderName" to sender.displayName,
                "memberUids" to conversation.memberUids,
                "text" to cleanText.take(4000),
                "reactions" to emptyMap<String, List<String>>(),
                "readByUids" to listOf(uid),
                "edited" to false,
                "deleted" to false,
                "expiresAt" to serverClock.computeExpiresAt(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            sender.avatarUrl?.takeIf { it.isNotBlank() }?.let { payload["senderAvatarUrl"] = it }
            if (replyTo != null) {
                payload["replyToMessageId"] = replyTo.id
                payload["replyToPreview"] = "Respondendo a ${replyTo.senderName}".take(200)
            }
            if (!attachmentUrl.isNullOrBlank() && !attachmentType.isNullOrBlank()) {
                payload["attachmentUrl"] = attachmentUrl.take(500)
                payload["attachmentType"] = attachmentType
                attachmentName?.takeIf { it.isNotBlank() }?.let { payload["attachmentName"] = it.take(160) }
                attachmentStoragePath?.takeIf { it.isNotBlank() }?.let { payload["attachmentStoragePath"] = it.take(500) }
            }
            if (preview != null) {
                payload["linkUrl"] = preview.url.take(500)
                payload["linkTitle"] = preview.title
                payload["linkPlatform"] = preview.platform
                payload["linkDescription"] = preview.description
                preview.thumbnailUrl?.let { payload["linkThumbnailUrl"] = it }
            }

            val summaryText = when {
                cleanText.isNotBlank() -> cleanText.take(120)
                attachmentType == "image" -> "📷 Imagem"
                else -> "📎 Arquivo"
            }

            val batch = db.batch()
            batch.set(msgRef, payload)
            batch.update(
                db.collection("conversations").document(conversation.id),
                mapOf(
                    "lastMessageText" to summaryText,
                    "lastMessageSenderUid" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun editMessage(conversationId: String, messageId: String, newText: String): Result<Unit> {
        val path = "conversations/$conversationId/messages/$messageId"
        return try {
            requireUserId()
            val clean = newText.trim()
            require(clean.isNotBlank() && clean.length <= 4000)
            db.document(path).update(
                mapOf(
                    "text" to clean,
                    "edited" to true,
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

    suspend fun deleteMessage(conversationId: String, message: ChatMessage): Result<Unit> {
        val path = "conversations/$conversationId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            db.document(path).delete().await()
            if (message.senderUid == uid && (!message.attachmentStoragePath.isNullOrBlank() || !message.attachmentUrl.isNullOrBlank())) {
                runCatching {
                    profileRepo?.deleteMedia(message.attachmentStoragePath, message.attachmentUrl)
                }
            }
            runCatching { refreshConversationLastMessage(conversationId, uid) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun purgeExpiredMessages(conversationId: String, expiredMessages: List<ChatMessage>) {
        if (expiredMessages.isEmpty()) return
        val uid = auth.currentUser?.uid ?: return
        var deletedAny = false
        for (msg in expiredMessages) {
            val deleted = runCatching {
                db.collection("conversations").document(conversationId)
                    .collection("messages").document(msg.id)
                    .delete()
                    .await()
            }.isSuccess
            if (deleted) {
                deletedAny = true
                if (msg.senderUid == uid && (!msg.attachmentStoragePath.isNullOrBlank() || !msg.attachmentUrl.isNullOrBlank())) {
                    runCatching {
                        profileRepo?.deleteMedia(msg.attachmentStoragePath, msg.attachmentUrl)
                    }
                }
            }
        }
        if (deletedAny) {
            runCatching { refreshConversationLastMessage(conversationId, uid) }
        }
    }

    private suspend fun refreshConversationLastMessage(conversationId: String, userId: String) {
        serverClock.ensureSynced(db, userId)
        val cutoff = serverClock.queryCutoffThreeHoursAgo()
        val snap = db.collection("conversations").document(conversationId)
            .collection("messages")
            .whereArrayContains("memberUids", userId)
            .whereGreaterThan("createdAt", cutoff)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(5)
            .get()
            .await()

        val nowMs = serverClock.nowServerMillis()
        val latest = snap.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .firstOrNull { !serverClock.isMessageExpired(it, nowMs = nowMs) }

        val convRef = db.collection("conversations").document(conversationId)
        if (latest == null) {
            convRef.update(
                mapOf(
                    "lastMessageText" to "",
                    "lastMessageSenderUid" to FieldValue.delete(),
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            val summary = when {
                latest.text.isNotBlank() -> latest.text.take(120)
                latest.attachmentType == "image" -> "📷 Imagem"
                else -> "📎 Arquivo"
            }
            convRef.update(
                mapOf(
                    "lastMessageText" to summary,
                    "lastMessageSenderUid" to latest.senderUid,
                    "lastMessageAt" to (latest.createdAt ?: FieldValue.serverTimestamp()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun toggleReaction(conversationId: String, message: ChatMessage, emoji: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "conversations/$conversationId/messages/${message.id}"
        return try {
            val updated = message.reactions.toMutableMap()
            val currentList = updated[emoji]?.toMutableList() ?: mutableListOf()
            if (currentList.contains(uid)) {
                currentList.remove(uid)
            } else {
                currentList.add(uid)
            }
            if (currentList.isEmpty()) {
                updated.remove(emoji)
            } else {
                updated[emoji] = currentList
            }
            db.document(path).update(
                mapOf(
                    "reactions" to updated,
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

    suspend fun markConversationRead(conversationId: String, messages: List<ChatMessage>): Unit {
        val uid = auth.currentUser?.uid ?: return
        val unread = messages.filter { !it.readByUids.contains(uid) && it.senderUid != uid }.takeLast(10)
        unread.forEach { msg ->
            runCatching {
                db.collection("conversations").document(conversationId)
                    .collection("messages").document(msg.id)
                    .update(
                        mapOf(
                            "readByUids" to (msg.readByUids + uid).distinct(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
            }
        }
    }

    suspend fun setTyping(conversationId: String, isTyping: Boolean): Unit {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val updateVal = if (isTyping) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            db.collection("conversations").document(conversationId).update(
                mapOf(
                    "typingUids" to updateVal,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun reportTarget(
        targetUid: String,
        targetMessageId: String? = null,
        serverId: String? = null,
        reason: String,
        details: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val docRef = db.collection("reports").document()
        return try {
            val payload = mutableMapOf<String, Any>(
                "reporterUid" to uid,
                "targetUid" to targetUid,
                "reason" to reason.trim().take(100).ifBlank { "Comportamento inadequado" },
                "details" to details.trim().take(1000),
                "status" to "open",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            targetMessageId?.let { payload["targetMessageId"] = it }
            serverId?.let { payload["serverId"] = it }
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }
}
