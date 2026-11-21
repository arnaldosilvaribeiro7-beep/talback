package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessage
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.preferences.AppPreferencesRepository
import com.example.ui.components.MessageListAndComposer
import com.example.ui.components.ProfileHeaderCard
import com.example.ui.theme.TalkbackTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
