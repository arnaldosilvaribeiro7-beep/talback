package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.Friendship
import com.example.data.model.PresenceStatus
import com.example.data.model.ServerMember
import com.example.data.model.ServerPermission
import com.example.data.model.UploadedMedia
import com.example.data.remote.GmailAuthValidator
import com.example.data.remote.LinkPreviewFetcher
import com.example.data.remote.ServerClock
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ExampleUnitTest {

    @Test
    fun `extractFirstUrl extracts http and https links`() {
        val fetcher = LinkPreviewFetcher()
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            fetcher.extractFirstUrl("Confira esse vídeo https://www.youtube.com/watch?v=dQw4w9WgXcQ agora!")
        )
        assertNull(fetcher.extractFirstUrl("Mensagem sem link algum"))
    }

    @Test
    fun `server member permission checks respect ADMIN_SERVER and granular permissions`() {
        val regular = ServerMember(
            userId = "u1",
            permissions = listOf(ServerPermission.SEND_MESSAGES.wire, ServerPermission.CONNECT_VOICE.wire)
        )
        assertTrue(regular.hasPermission(ServerPermission.SEND_MESSAGES))
        assertFalse(regular.hasPermission(ServerPermission.MANAGE_CHANNELS))

        val admin = ServerMember(
            userId = "u2",
            permissions = listOf(ServerPermission.ADMIN_SERVER.wire)
        )
        assertTrue(admin.hasPermission(ServerPermission.MANAGE_CHANNELS))
        assertTrue(admin.hasPermission(ServerPermission.MANAGE_ROLES))
        assertTrue(admin.hasPermission(ServerPermission.MANAGE_MEMBERS))
    }

    @Test
    fun `friendship resolves peer details accurately`() {
        val f = Friendship(
            id = "u1_u2",
            participantUids = listOf("u1", "u2"),
            userAUid = "u1",
            userAName = "Ana",
            userAUsername = "ana_dev",
            userBUid = "u2",
            userBName = "Bruno",
            userBUsername = "bruno_ui"
        )
        assertEquals("u2", f.peerUid("u1"))
        assertEquals("Bruno", f.peerName("u1"))
        assertEquals("bruno_ui", f.peerUsername("u1"))
        assertEquals("u1", f.peerUid("u2"))
        assertEquals("Ana", f.peerName("u2"))
    }

    @Test
    fun `presence status parses wire values with safe fallback`() {
        assertEquals(PresenceStatus.DND, PresenceStatus.fromWire("dnd"))
        assertEquals(PresenceStatus.IDLE, PresenceStatus.fromWire("idle"))
        assertEquals(PresenceStatus.ONLINE, PresenceStatus.fromWire("unknown_status"))
    }

    @Test
    fun `server clock stays monotonic despite device wall clock manipulation`() {
        var elapsed = 1_000L
        var wall = 1_700_000_000_000L
        val clock = ServerClock(
            elapsedRealtimeProvider = { elapsed },
            wallClockProvider = { wall }
        )

        val serverBase = 1_750_000_000_000L
        clock.calibrate(serverBase)

        // User tampers with device wall clock (+10 hours); server clock must ignore wall clock
        wall += 10L * 3_600_000L
        elapsed += 60_000L // +1 real minute
        assertEquals(serverBase + 60_000L, clock.nowServerMillis())

        val expiresAt = clock.computeExpiresAt()
        assertEquals(serverBase + 60_000L + ServerClock.MESSAGE_TTL_MILLIS, expiresAt.toDate().time)

        val logExpiry = clock.computeExpiresAt(ServerClock.MODERATION_LOG_TTL_MILLIS)
        assertEquals(serverBase + 60_000L + ServerClock.MODERATION_LOG_TTL_MILLIS, logExpiry.toDate().time)

        val signalExpiry = clock.computeExpiresAt(ServerClock.SIGNAL_TTL_MILLIS)
        assertEquals(serverBase + 60_000L + ServerClock.SIGNAL_TTL_MILLIS, signalExpiry.toDate().time)
    }

    @Test
    fun `message expiration triggers at exact 3h boundary and honors pending writes`() {
        var elapsed = 0L
        val clock = ServerClock(
            elapsedRealtimeProvider = { elapsed },
            wallClockProvider = { 0L }
        )
        val t0 = 1_750_000_000_000L
        clock.calibrate(t0)

        val msg = ChatMessage(
            id = "m1",
            senderUid = "u1",
            senderName = "Ana",
            text = "Oi",
            expiresAt = Timestamp(Date(t0 + ServerClock.MESSAGE_TTL_MILLIS)),
            createdAt = Timestamp(Date(t0))
        )

        elapsed = ServerClock.MESSAGE_TTL_MILLIS - 1L
        assertFalse(clock.isMessageExpired(msg))

        elapsed = ServerClock.MESSAGE_TTL_MILLIS
        assertTrue(clock.isMessageExpired(msg))
        assertTrue(clock.isTimestampExpired(msg.createdAt))

        // 24h moderation log expiration boundary
        elapsed = ServerClock.MODERATION_LOG_TTL_MILLIS - 1L
        assertFalse(
            clock.isRecordExpired(
                expiresAt = Timestamp(Date(t0 + ServerClock.MODERATION_LOG_TTL_MILLIS)),
                createdAt = Timestamp(Date(t0)),
                ttlMillis = ServerClock.MODERATION_LOG_TTL_MILLIS
            )
        )
        elapsed = ServerClock.MODERATION_LOG_TTL_MILLIS
        assertTrue(
            clock.isRecordExpired(
                expiresAt = Timestamp(Date(t0 + ServerClock.MODERATION_LOG_TTL_MILLIS)),
                createdAt = Timestamp(Date(t0)),
                ttlMillis = ServerClock.MODERATION_LOG_TTL_MILLIS
            )
        )

        // Pending local write with old estimated createdAt does not falsely expire if expiresAt is still future
        val pendingMsg = ChatMessage(
            id = "m_pending",
            senderUid = "u1",
            senderName = "Ana",
            text = "Agora",
            expiresAt = Timestamp(Date(t0 + ServerClock.MESSAGE_TTL_MILLIS + 60_000L)),
            createdAt = Timestamp(Date(t0 - 1_000_000L))
        )
        elapsed = 0L
        assertFalse(clock.isMessageExpired(pendingMsg, hasPendingWrites = true))

        val uploaded = UploadedMedia(
            downloadUrl = "https://firebasestorage.googleapis.com/v0/b/x/o/file",
            fileName = "foto.jpg",
            storagePath = "chat_attachments/u1/foto.jpg"
        )
        assertEquals("chat_attachments/u1/foto.jpg", uploaded.storagePath)
    }

    @Test
    fun `gmail otp validator accepts only @gmail com and 6 digit numeric codes`() {
        assertEquals("usuario.teste+1@gmail.com", GmailAuthValidator.normalizeGmail("  Usuario.Teste+1@Gmail.COM "))
        assertTrue(GmailAuthValidator.isValidGmail("dev.talkback@gmail.com"))
        assertFalse(GmailAuthValidator.isValidGmail("dev@outlook.com"))
        assertFalse(GmailAuthValidator.isValidGmail("dev@hotmail.com"))
        assertFalse(GmailAuthValidator.isValidGmail("invalido"))

        assertEquals("ana.dev_01", GmailAuthValidator.normalizeUsername(" @Ana.Dev_01 "))
        assertNull(GmailAuthValidator.normalizeUsername("ab"))

        assertTrue(GmailAuthValidator.isValidSixDigitCode("123456"))
        assertFalse(GmailAuthValidator.isValidSixDigitCode("12345"))
        assertFalse(GmailAuthValidator.isValidSixDigitCode("12a456"))
    }
}
