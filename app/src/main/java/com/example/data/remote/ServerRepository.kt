package com.example.data.remote

import com.example.data.model.ChatMessage
import com.example.data.model.ModerationLog
import com.example.data.model.Server
import com.example.data.model.ServerChannel
import com.example.data.model.ServerInvite
import com.example.data.model.ServerMember
import com.example.data.model.ServerPermission
import com.example.data.model.ServerRole
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
import java.util.UUID

class ServerRepository(
    private val db: FirebaseFirestore,
    private val linkPreviewFetcher: LinkPreviewFetcher = LinkPreviewFetcher(),
    private val auth: FirebaseAuth = Firebase.auth,
    private val serverClock: ServerClock = ServerClock(),
    private val profileRepo: ProfileRepository? = null
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Usuário precisa estar autenticado.")

    fun observeUserServers(userId: String): Flow<List<Server>> = flow {
        val path = "servers"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap -> snap.toObjects(Server::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.LIST, path)
                    }
                    throw unwrapped
                }
        )
    }

    fun observeChannels(serverId: String, userId: String): Flow<List<ServerChannel>> = flow {
        val path = "servers/$serverId/channels"
        emitAll(
            db.collection("servers").document(serverId).collection("channels")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ServerChannel::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.position }
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

    fun observeRoles(serverId: String, userId: String): Flow<List<ServerRole>> = flow {
        val path = "servers/$serverId/roles"
        emitAll(
            db.collection("servers").document(serverId).collection("roles")
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ServerRole::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.position }
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

    fun observeMyMembership(serverId: String, userId: String): Flow<ServerMember?> = flow {
        val path = "servers/$serverId/members/$userId"
        emitAll(
            db.collection("servers").document(serverId).collection("members").document(userId)
                .snapshots()
                .map { it.toObject(ServerMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val unwrapped = unwrapFirestoreError(error)
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(unwrapped, OperationType.GET, path)
                    }
                    throw unwrapped
                }
        )
    }

    suspend fun fetchServerMembers(server: Server): List<ServerMember> {
        return server.memberUids.mapNotNull { uid ->
            runCatching {
                db.collection("servers").document(server.id)
                    .collection("members").document(uid).get().await()
                    .toObject(ServerMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }.getOrNull()
        }
    }

    fun observeChannelMessages(
        serverId: String,
        channelId: String,
        userId: String,
        pageSize: Long = 50L
    ): Flow<List<ChatMessage>> = flow {
        val path = "servers/$serverId/channels/$channelId/messages"
        serverClock.ensureSynced(db, userId)
        val cutoff = serverClock.queryCutoffThreeHoursAgo()
        emitAll(
            db.collection("servers").document(serverId)
                .collection("channels").document(channelId)
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

    fun observeModerationLogs(serverId: String, userId: String): Flow<List<ModerationLog>> = flow {
        val path = "servers/$serverId/moderation_logs"
        serverClock.ensureSynced(db, userId)
        val cutoff = serverClock.queryCutoffAgo(ServerClock.MODERATION_LOG_TTL_MILLIS)
        emitAll(
            db.collection("servers").document(serverId).collection("moderation_logs")
                .whereArrayContains("memberUids", userId)
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .snapshots()
                .map { snap ->
                    val nowMs = serverClock.nowServerMillis()
                    snap.documents.mapNotNull { doc ->
                        val pending = doc.metadata.hasPendingWrites()
                        val log = doc.toObject(
                            ModerationLog::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: return@mapNotNull null
                        log.takeIf {
                            !serverClock.isRecordExpired(
                                expiresAt = it.expiresAt,
                                createdAt = it.createdAt,
                                ttlMillis = ServerClock.MODERATION_LOG_TTL_MILLIS,
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

    suspend fun createServer(name: String, description: String, creator: UserProfile): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.length > 80) {
            return Result.failure(IllegalArgumentException("Nome do servidor deve ter entre 1 e 80 caracteres."))
        }
        val serverRef = db.collection("servers").document()
        val inviteCode = "TB${UUID.randomUUID().toString().take(6).uppercase()}"
        return try {
            val serverPayload = mapOf(
                "name" to cleanName,
                "description" to description.trim().take(300),
                "ownerUid" to uid,
                "memberUids" to listOf(uid),
                "inviteCode" to inviteCode,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val memberPayload = mutableMapOf<String, Any>(
                "userId" to uid,
                "serverId" to serverRef.id,
                "displayName" to creator.displayName,
                "roleId" to "admin",
                "roleName" to "Administrador",
                "permissions" to ServerPermission.ALL_WIRES,
                "joinedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            creator.avatarUrl?.takeIf { it.isNotBlank() }?.let { memberPayload["avatarUrl"] = it }

            val adminRolePayload = mapOf(
                "serverId" to serverRef.id,
                "name" to "Administrador",
                "colorHex" to 0xFFF23F43L,
                "permissions" to ServerPermission.ALL_WIRES,
                "position" to 0L,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val modRolePayload = mapOf(
                "serverId" to serverRef.id,
                "name" to "Moderador",
                "colorHex" to 0xFF23A55AL,
                "permissions" to ServerPermission.MODERATOR_WIRES,
                "position" to 1L,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val memberRolePayload = mapOf(
                "serverId" to serverRef.id,
                "name" to "Membro",
                "colorHex" to 0xFF99AAB5L,
                "permissions" to ServerPermission.DEFAULT_MEMBER_WIRES,
                "position" to 2L,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val textChannelPayload = mapOf(
                "serverId" to serverRef.id,
                "name" to "geral",
                "topic" to "Canal principal da comunidade",
                "type" to "text",
                "position" to 0L,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val voiceChannelPayload = mapOf(
                "serverId" to serverRef.id,
                "name" to "Voz Geral",
                "topic" to "Sala de áudio e compartilhamento de tela",
                "type" to "voice",
                "position" to 1L,
                "memberUids" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val invitePayload = mapOf(
                "code" to inviteCode,
                "serverId" to serverRef.id,
                "serverName" to cleanName,
                "serverOwnerUid" to uid,
                "creatorUid" to uid,
                "maxUses" to 0L,
                "uses" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            // Create server + founder membership first so subcollection security rules validate owner permissions
            val initBatch = db.batch()
            initBatch.set(serverRef, serverPayload)
            initBatch.set(serverRef.collection("members").document(uid), memberPayload)
            initBatch.commit().await()

            val setupBatch = db.batch()
            setupBatch.set(serverRef.collection("roles").document("admin"), adminRolePayload)
            setupBatch.set(serverRef.collection("roles").document("moderator"), modRolePayload)
            setupBatch.set(serverRef.collection("roles").document("member"), memberRolePayload)
            setupBatch.set(serverRef.collection("channels").document(), textChannelPayload)
            setupBatch.set(serverRef.collection("channels").document(), voiceChannelPayload)
            setupBatch.set(db.collection("invites").document(inviteCode), invitePayload)
            setupBatch.commit().await()

            Result.success(serverRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, serverRef.path)
            Result.failure(e)
        }
    }

    suspend fun joinServerByInvite(rawCode: String, user: UserProfile): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val code = rawCode.trim().uppercase()
        if (!code.matches(Regex("^[A-Za-z0-9_\\-]{4,32}$"))) {
            return Result.failure(IllegalArgumentException("Código de convite inválido."))
        }
        return try {
            val inviteSnap = db.collection("invites").document(code).get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(IllegalStateException("Convite não encontrado."))
            }
            val invite = inviteSnap.toObject(ServerInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Convite inválido."))
            if (invite.maxUses > 0 && invite.uses >= invite.maxUses) {
                return Result.failure(IllegalStateException("Este convite atingiu o limite de usos."))
            }

            val banSnap = db.collection("servers").document(invite.serverId).collection("bans").document(uid).get().await()
            if (banSnap.exists()) {
                return Result.failure(IllegalStateException("Você foi banido deste servidor."))
            }

            val serverRef = db.collection("servers").document(invite.serverId)
            val memberRef = serverRef.collection("members").document(uid)
            val memberPayload = mutableMapOf<String, Any>(
                "userId" to uid,
                "serverId" to invite.serverId,
                "displayName" to user.displayName,
                "roleId" to "member",
                "roleName" to "Membro",
                "permissions" to ServerPermission.DEFAULT_MEMBER_WIRES,
                "joinedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            user.avatarUrl?.takeIf { it.isNotBlank() }?.let { memberPayload["avatarUrl"] = it }

            val joinBatch = db.batch()
            joinBatch.update(
                serverRef,
                mapOf(
                    "memberUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            joinBatch.set(memberRef, memberPayload)
            joinBatch.update(
                db.collection("invites").document(code),
                mapOf(
                    "uses" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            joinBatch.commit().await()

            // Sync memberUids on channels and roles so the new member receives real-time updates
            val channels = serverRef.collection("channels").get().await()
            val roles = serverRef.collection("roles").get().await()
            val syncBatch = db.batch()
            channels.documents.forEach { doc ->
                syncBatch.update(doc.reference, mapOf("memberUids" to FieldValue.arrayUnion(uid), "updatedAt" to FieldValue.serverTimestamp()))
            }
            roles.documents.forEach { doc ->
                syncBatch.update(doc.reference, mapOf("memberUids" to FieldValue.arrayUnion(uid), "updatedAt" to FieldValue.serverTimestamp()))
            }
            syncBatch.commit().await()

            Result.success(invite.serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "invites/$code")
            Result.failure(e)
        }
    }

    suspend fun createInvite(server: Server, maxUses: Long = 0L): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val code = "TB${UUID.randomUUID().toString().take(6).uppercase()}"
        return try {
            val payload = mutableMapOf<String, Any>(
                "code" to code,
                "serverId" to server.id,
                "serverName" to server.name,
                "serverOwnerUid" to server.ownerUid,
                "creatorUid" to uid,
                "maxUses" to maxUses.coerceIn(0L, 1000L),
                "uses" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            server.iconUrl?.takeIf { it.isNotBlank() }?.let { payload["serverIconUrl"] = it }
            db.collection("invites").document(code).set(payload).await()
            Result.success(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "invites/$code")
            Result.failure(e)
        }
    }

    suspend fun leaveServer(server: Server): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        if (server.ownerUid == uid) {
            return Result.failure(IllegalStateException("O dono não pode sair sem transferir ou excluir o servidor."))
        }
        return try {
            val serverRef = db.collection("servers").document(server.id)
            val batch = db.batch()
            batch.update(
                serverRef,
                mapOf(
                    "memberUids" to FieldValue.arrayRemove(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.delete(serverRef.collection("members").document(uid))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "servers/${server.id}")
            Result.failure(e)
        }
    }

    suspend fun createChannel(
        server: Server,
        name: String,
        topic: String,
        type: String,
        position: Long
    ): Result<Unit> {
        val path = "servers/${server.id}/channels"
        return try {
            requireUserId()
            val clean = name.trim().lowercase().replace(" ", "-").take(60)
            require(clean.isNotBlank()) { "Informe o nome do canal." }
            val payload = mutableMapOf<String, Any>(
                "serverId" to server.id,
                "name" to clean,
                "type" to type,
                "position" to position,
                "memberUids" to server.memberUids,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            topic.trim().takeIf { it.isNotBlank() }?.let { payload["topic"] = it.take(200) }
            db.collection("servers").document(server.id).collection("channels").document().set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateChannel(serverId: String, channelId: String, name: String, topic: String, position: Long): Result<Unit> {
        val path = "servers/$serverId/channels/$channelId"
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>(
                "name" to name.trim().lowercase().replace(" ", "-").take(60),
                "position" to position,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (topic.isNotBlank()) updates["topic"] = topic.trim().take(200)
            db.document(path).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteChannel(serverId: String, channelId: String): Result<Unit> {
        val path = "servers/$serverId/channels/$channelId"
        return try {
            requireUserId()
            db.document(path).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun createOrUpdateRole(
        server: Server,
        roleId: String?,
        name: String,
        colorHex: Long,
        permissions: List<String>,
        position: Long
    ): Result<Unit> {
        val path = "servers/${server.id}/roles"
        return try {
            requireUserId()
            val clean = name.trim().take(50)
            require(clean.isNotBlank()) { "Informe o nome do cargo." }
            val ref = if (roleId != null) {
                db.collection("servers").document(server.id).collection("roles").document(roleId)
            } else {
                db.collection("servers").document(server.id).collection("roles").document()
            }
            if (roleId == null) {
                ref.set(
                    mapOf(
                        "serverId" to server.id,
                        "name" to clean,
                        "colorHex" to colorHex,
                        "permissions" to permissions.distinct().take(20),
                        "position" to position,
                        "memberUids" to server.memberUids,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.update(
                    mapOf(
                        "name" to clean,
                        "colorHex" to colorHex,
                        "permissions" to permissions.distinct().take(20),
                        "position" to position,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun assignRoleToMember(
        server: Server,
        actor: UserProfile,
        targetUid: String,
        role: ServerRole
    ): Result<Unit> {
        val path = "servers/${server.id}/members/$targetUid"
        return try {
            val uid = requireUserId()
            serverClock.ensureSynced(db, uid)
            val batch = db.batch()
            batch.update(
                db.document(path),
                mapOf(
                    "roleId" to role.id,
                    "roleName" to role.name,
                    "permissions" to role.permissions,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                db.collection("servers").document(server.id).collection("moderation_logs").document(),
                mapOf(
                    "serverId" to server.id,
                    "actorUid" to actor.userId,
                    "actorName" to actor.displayName,
                    "action" to "role_change",
                    "targetUid" to targetUid,
                    "details" to "Cargo alterado para ${role.name}",
                    "memberUids" to server.memberUids,
                    "expiresAt" to serverClock.computeExpiresAt(ServerClock.MODERATION_LOG_TTL_MILLIS),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun kickOrBanMember(
        server: Server,
        actor: UserProfile,
        targetUid: String,
        ban: Boolean,
        reason: String
    ): Result<Unit> {
        if (targetUid == server.ownerUid) {
            return Result.failure(IllegalArgumentException("Não é possível remover o dono do servidor."))
        }
        val path = "servers/${server.id}"
        return try {
            val uid = requireUserId()
            serverClock.ensureSynced(db, uid)
            val serverRef = db.document(path)
            val batch = db.batch()
            batch.update(
                serverRef,
                mapOf(
                    "memberUids" to FieldValue.arrayRemove(targetUid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.delete(serverRef.collection("members").document(targetUid))
            if (ban) {
                batch.set(
                    serverRef.collection("bans").document(targetUid),
                    mapOf(
                        "serverId" to server.id,
                        "userId" to targetUid,
                        "reason" to reason.trim().take(300).ifBlank { "Violação das regras" },
                        "bannedByUid" to actor.userId,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(
                serverRef.collection("moderation_logs").document(),
                mapOf(
                    "serverId" to server.id,
                    "actorUid" to actor.userId,
                    "actorName" to actor.displayName,
                    "action" to if (ban) "ban" else "kick",
                    "targetUid" to targetUid,
                    "details" to reason.trim().take(300).ifBlank { if (ban) "Membro banido" else "Membro expulso" },
                    "memberUids" to server.memberUids.filter { it != targetUid },
                    "expiresAt" to serverClock.computeExpiresAt(ServerClock.MODERATION_LOG_TTL_MILLIS),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateServerInfo(serverId: String, name: String, description: String, iconUrl: String?): Result<Unit> {
        val path = "servers/$serverId"
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>(
                "name" to name.trim().take(80),
                "description" to description.trim().take(300),
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

    suspend fun sendChannelMessage(
        server: Server,
        channelId: String,
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
        val msgRef = db.collection("servers").document(server.id)
            .collection("channels").document(channelId)
            .collection("messages").document()
        return try {
            serverClock.ensureSynced(db, uid)
            val firstUrl = linkPreviewFetcher.extractFirstUrl(cleanText)
            val preview = firstUrl?.let { linkPreviewFetcher.fetchMetadata(it) }

            val payload = mutableMapOf<String, Any>(
                "serverId" to server.id,
                "channelId" to channelId,
                "senderUid" to uid,
                "senderName" to sender.displayName,
                "memberUids" to server.memberUids,
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
            msgRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleChannelReaction(
        serverId: String,
        channelId: String,
        message: ChatMessage,
        emoji: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado."))
        val path = "servers/$serverId/channels/$channelId/messages/${message.id}"
        return try {
            val updated = message.reactions.toMutableMap()
            val list = updated[emoji]?.toMutableList() ?: mutableListOf()
            if (list.contains(uid)) list.remove(uid) else list.add(uid)
            if (list.isEmpty()) updated.remove(emoji) else updated[emoji] = list
            db.document(path).update(
                mapOf("reactions" to updated, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun editChannelMessage(serverId: String, channelId: String, messageId: String, newText: String): Result<Unit> {
        val path = "servers/$serverId/channels/$channelId/messages/$messageId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "text" to newText.trim().take(4000),
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

    suspend fun deleteChannelMessage(serverId: String, channelId: String, message: ChatMessage): Result<Unit> {
        val path = "servers/$serverId/channels/$channelId/messages/${message.id}"
        return try {
            val uid = requireUserId()
            db.document(path).delete().await()
            if (message.senderUid == uid && (!message.attachmentStoragePath.isNullOrBlank() || !message.attachmentUrl.isNullOrBlank())) {
                runCatching {
                    profileRepo?.deleteMedia(message.attachmentStoragePath, message.attachmentUrl)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun purgeExpiredChannelMessages(serverId: String, channelId: String, expiredMessages: List<ChatMessage>) {
        if (expiredMessages.isEmpty()) return
        val uid = auth.currentUser?.uid ?: return
        for (msg in expiredMessages) {
            val deleted = runCatching {
                db.collection("servers").document(serverId)
                    .collection("channels").document(channelId)
                    .collection("messages").document(msg.id)
                    .delete()
                    .await()
            }.isSuccess
            if (deleted && msg.senderUid == uid && (!msg.attachmentStoragePath.isNullOrBlank() || !msg.attachmentUrl.isNullOrBlank())) {
                runCatching {
                    profileRepo?.deleteMedia(msg.attachmentStoragePath, msg.attachmentUrl)
                }
            }
        }
    }
}
