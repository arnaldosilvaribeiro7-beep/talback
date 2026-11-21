package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.preferences.AppPreferencesRepository
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
    fun `app metadata strings and firestore database id are configured for Talkback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Talkback", context.getString(R.string.app_name))
        val dbId = context.getString(R.string.firestore_database_id)
        assertTrue(dbId.isNotBlank())
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
}
