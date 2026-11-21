package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessage
import com.example.data.model.Friendship
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.LocalFriendsCache
import com.example.data.remote.AuthRepository
import com.example.ui.components.MessageListAndComposer
import com.example.ui.components.ProfileHeaderCard
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.TalkbackTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `app metadata strings and firebase resource ids are configured for Talkback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Talkback", context.getString(R.string.app_name))
        val dbId = context.getString(R.string.firestore_database_id)
        assertTrue(dbId.isNotBlank())
        val webClientId = context.getString(R.string.default_web_client_id)
        assertTrue(webClientId.endsWith(".apps.googleusercontent.com"))
    }

    @Test
    fun `theme mode persists via DataStore preferences`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context)
        prefs.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, prefs.themeMode.first())
        prefs.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, prefs.themeMode.first())
    }

    @Test
    fun `profile header card renders displayName handle bio and custom status`() {
        val sample = UserProfile(
            id = "uid_1",
            userId = "uid_1",
            username = "carla_tb",
            displayName = "Carla Mendes",
            bio = "Engenheira Android & Comunidades",
            statusText = "Em chamada de voz",
            presence = "online"
        )
        composeTestRule.setContent {
            TalkbackTheme(themeMode = ThemeMode.DARK) {
                ProfileHeaderCard(profile = sample)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Carla Mendes").assertIsDisplayed()
        composeTestRule.onNodeWithText("@carla_tb").assertIsDisplayed()
        composeTestRule.onNodeWithText("Em chamada de voz").assertIsDisplayed()
        composeTestRule.onNodeWithText("Engenheira Android & Comunidades").assertIsDisplayed()
    }

    @Test
    fun `main activity launches without crashing`() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertTrue(!activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `message list replaces reply preview when target message is removed or expired`() {
        val replyMsg = ChatMessage(
            id = "m_reply",
            senderUid = "u2",
            senderName = "Bruno",
            text = "Entendi!",
            replyToMessageId = "m_deleted",
            replyToPreview = "Respondendo a Ana"
        )
        composeTestRule.setContent {
            TalkbackTheme(themeMode = ThemeMode.DARK) {
                MessageListAndComposer(
                    messages = listOf(replyMsg),
                    currentUserId = "u1",
                    typingIndicatorText = null,
                    canModerateMessages = true,
                    onLoadMore = {},
                    onSendMessage = { _, _ -> },
                    onAttachMedia = { _, _, _, _ -> },
                    onTypingChanged = {},
                    onToggleReaction = { _, _ -> },
                    onEditMessage = { _, _ -> },
                    onDeleteMessage = {},
                    onReportMessage = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Respondendo a mensagem expirada ou removida").assertIsDisplayed()
        composeTestRule.onNodeWithText("Entendi!").assertIsDisplayed()
    }

    @Test
    fun `local friends cache persists added friends, peer profiles, and local favorites`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = LocalFriendsCache(context)
        val friend = Friendship(
            id = "me_peer1",
            participantUids = listOf("me", "peer1"),
            userAUid = "me",
            userAName = "Eu",
            userAUsername = "eu_dev",
            userBUid = "peer1",
            userBName = "Lucas",
            userBUsername = "lucas_tb"
        )
        cache.syncFriendships("me", listOf(friend))
        cache.updateCachedPeerProfile(
            "me",
            UserProfile(
                userId = "peer1",
                username = "lucas_tb",
                displayName = "Lucas Oliveira",
                statusText = "Jogando",
                presence = "online"
            )
        )
        val isFav = cache.toggleFavoritePeer("me", "peer1")
        assertTrue(isFav)

        val loadedFriends = cache.getCachedFriendships("me")
        assertEquals(1, loadedFriends.size)
        assertEquals("peer1", loadedFriends.first().peerUid("me"))

        val peerSummary = cache.getCachedPeerProfiles("me")["peer1"]
        assertEquals("Lucas Oliveira", peerSummary?.displayName)
        assertEquals("online", peerSummary?.presence)
        assertTrue(cache.observeFavoritePeerUids("me").first().contains("peer1"))

        cache.removeFriendship("me", "me_peer1")
        assertTrue(cache.getCachedFriendships("me").isEmpty())
        assertFalse(cache.observeFavoritePeerUids("me").first().contains("peer1"))
    }

    @Test
    fun `auth screen displays google sign-in and gmail 6-digit otp options`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(context.getString(R.string.google_app_id))
                    .setApiKey(context.getString(R.string.google_api_key))
                    .setProjectId(context.getString(R.string.project_id))
                    .build()
            )
        }
        val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
        val repo = AuthRepository(db = db)

        composeTestRule.setContent {
            TalkbackTheme(themeMode = ThemeMode.DARK) {
                AuthScreen(authRepository = repo, onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("gmail_email_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_otp_button").performScrollTo().assertIsDisplayed()
    }
}
