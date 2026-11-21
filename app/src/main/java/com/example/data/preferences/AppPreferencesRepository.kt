package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioProcessingSettings
import com.example.data.model.SfuConfiguration
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.talkbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "talkback_prefs")

class AppPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.talkbackDataStore

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NOISE_SUPPRESSION = booleanPreferencesKey("audio_noise_suppression")
        val ECHO_CANCELLATION = booleanPreferencesKey("audio_echo_cancellation")
        val AUTO_GAIN_CONTROL = booleanPreferencesKey("audio_auto_gain_control")
        val BACKGROUND_REDUCTION = booleanPreferencesKey("audio_bg_reduction")
        val AUDIO_OUTPUT = stringPreferencesKey("audio_output_route")
        val SFU_URL = stringPreferencesKey("sfu_signaling_url")
        val TURN_URL = stringPreferencesKey("turn_server_url")
        val TURN_USER = stringPreferencesKey("turn_username")
        val TURN_CRED = stringPreferencesKey("turn_credential")
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.DARK
    }

    val audioSettings: Flow<AudioProcessingSettings> = dataStore.data.map { prefs ->
        val routeRaw = prefs[Keys.AUDIO_OUTPUT] ?: AudioOutputRoute.SPEAKER.name
        AudioProcessingSettings(
            noiseSuppression = prefs[Keys.NOISE_SUPPRESSION] ?: true,
            echoCancellation = prefs[Keys.ECHO_CANCELLATION] ?: true,
            autoGainControl = prefs[Keys.AUTO_GAIN_CONTROL] ?: true,
            backgroundNoiseReduction = prefs[Keys.BACKGROUND_REDUCTION] ?: true,
            preferredOutput = AudioOutputRoute.entries.firstOrNull { it.name == routeRaw }
                ?: AudioOutputRoute.SPEAKER
        )
    }

    val sfuConfiguration: Flow<SfuConfiguration> = dataStore.data.map { prefs ->
        SfuConfiguration(
            signalingUrl = prefs[Keys.SFU_URL] ?: "",
            turnServerUrl = prefs[Keys.TURN_URL] ?: "",
            turnUsername = prefs[Keys.TURN_USER] ?: "",
            turnCredential = prefs[Keys.TURN_CRED] ?: ""
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateAudioSettings(settings: AudioProcessingSettings) {
        dataStore.edit {
            it[Keys.NOISE_SUPPRESSION] = settings.noiseSuppression
            it[Keys.ECHO_CANCELLATION] = settings.echoCancellation
            it[Keys.AUTO_GAIN_CONTROL] = settings.autoGainControl
            it[Keys.BACKGROUND_REDUCTION] = settings.backgroundNoiseReduction
            it[Keys.AUDIO_OUTPUT] = settings.preferredOutput.name
        }
    }

    suspend fun updateSfuConfiguration(config: SfuConfiguration) {
        dataStore.edit {
            it[Keys.SFU_URL] = config.signalingUrl.trim()
            it[Keys.TURN_URL] = config.turnServerUrl.trim()
            it[Keys.TURN_USER] = config.turnUsername.trim()
            it[Keys.TURN_CRED] = config.turnCredential
        }
    }
}
