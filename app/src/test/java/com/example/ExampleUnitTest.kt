package com.example

import com.example.data.model.Friendship
import com.example.data.model.PresenceStatus
import com.example.data.model.ServerMember
import com.example.data.model.ServerPermission
import com.example.data.remote.LinkPreviewFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
