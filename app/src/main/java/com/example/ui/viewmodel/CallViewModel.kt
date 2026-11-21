package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioProcessingSettings
import com.example.data.model.CallSession
import com.example.data.model.ScreenAudioSource
import com.example.data.model.SfuConfiguration
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.remote.CallRepository
import com.example.data.remote.unwrapFirestoreError
import com.example.media.CallForegroundService
import com.example.media.WebRtcCallEngine
import com.example.media.WebRtcRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallViewModel(
    private val appContext: Context,
    private val currentUserId: String,
    private val callRepo: CallRepository,
    private val prefsRepo: AppPreferencesRepository
) : ViewModel() {

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents = _toastEvents.asSharedFlow()

    val callHistory: StateFlow<UiState<List<CallSession>>> = callRepo.observeUserCalls(currentUserId)
        .map<List<CallSession>, UiState<List<CallSession>>> { UiState.Success(it) }
        .catch { e ->
            val root = unwrapFirestoreError(e)
            emit(UiState.Error(root.message ?: "Erro ao carregar chamadas"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    val activeCall = _activeCall.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    val durationSeconds = _durationSeconds.asStateFlow()

    private val _webRtcState = MutableStateFlow(WebRtcRuntimeState())
    val webRtcState: StateFlow<WebRtcRuntimeState> = _webRtcState.asStateFlow()

    private var webRtcEngine: WebRtcCallEngine? = null
    private var signalJob: Job? = null
    private var callSessionJob: Job? = null
    private var timerJob: Job? = null
    private var stateForwardJob: Job? = null

    val eglBaseContext get() = webRtcEngine?.eglBase?.eglBaseContext

    fun startCall(
        caller: UserProfile,
        contextType: String,
        contextId: String,
        serverId: String? = null,
        callType: String,
        participantUids: List<String>
    ) {
        viewModelScope.launch {
            val sfu = prefsRepo.sfuConfiguration.first()
            callRepo.startCall(
                caller = caller,
                contextType = contextType,
                contextId = contextId,
                serverId = serverId,
                callType = callType,
                participantUids = participantUids,
                sfuRoomUrl = sfu.signalingUrl.takeIf { it.isNotBlank() }
            ).onSuccess { session ->
                attachToCallSession(session, isInitiator = true)
            }.onFailure {
                _toastEvents.tryEmit(it.message ?: "Não foi possível iniciar chamada")
            }
        }
    }

    fun acceptIncomingCall(call: CallSession) {
        viewModelScope.launch {
            callRepo.acceptCall(call.id)
                .onSuccess { attachToCallSession(call.copy(status = "active"), isInitiator = false) }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao atender chamada") }
        }
    }

    fun declineIncomingCall(callId: String) {
        viewModelScope.launch {
            callRepo.declineCall(callId)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao recusar chamada") }
        }
    }

    fun endActiveCall() {
        val current = _activeCall.value ?: return
        viewModelScope.launch {
            callRepo.endCall(current.id)
            cleanupCallResources()
        }
    }

    private fun attachToCallSession(session: CallSession, isInitiator: Boolean) {
        cleanupCallResources()
        _activeCall.value = session
        CallForegroundService.startForCall(appContext, session.callerName)

        viewModelScope.launch {
            val audioSettings = prefsRepo.audioSettings.first()
            val sfu = prefsRepo.sfuConfiguration.first()
            val engine = WebRtcCallEngine(
                context = appContext,
                onSendSignal = { toUid, type, sdp, ice, mid, mLine ->
                    viewModelScope.launch {
                        callRepo.sendSignal(
                            callId = session.id,
                            participantUids = session.participantUids,
                            toUid = toUid,
                            type = type,
                            sdp = sdp,
                            iceCandidate = ice,
                            sdpMid = mid,
                            sdpMLineIndex = mLine
                        )
                    }
                },
                onScreenShareStopped = {
                    viewModelScope.launch {
                        callRepo.updateScreenSharing(session.id, null)
                    }
                }
            )
            engine.initialize(audioSettings, sfu)
            engine.startLocalMedia(enableVideo = session.callType == "video")
            webRtcEngine = engine

            stateForwardJob = viewModelScope.launch {
                engine.state.collect { _webRtcState.value = it }
            }

            if (isInitiator) {
                session.participantUids.filter { it != currentUserId }.forEach { peerUid ->
                    engine.createOfferForPeer(peerUid)
                }
            }

            callSessionJob = viewModelScope.launch {
                callRepo.observeCall(session.id)
                    .catch { e -> if (e is CancellationException) throw e }
                    .collect { updated ->
                        if (updated == null || updated.status == "ended" || updated.status == "declined") {
                            cleanupCallResources()
                        } else {
                            _activeCall.value = updated
                        }
                    }
            }

            signalJob = viewModelScope.launch {
                callRepo.observeSignals(session.id, currentUserId)
                    .catch { e -> if (e is CancellationException) throw e }
                    .collect { signals ->
                        signals.filter { it.toUid == currentUserId && it.fromUid != currentUserId }
                            .forEach { engine.handleIncomingSignal(it) }
                    }
            }

            timerJob = viewModelScope.launch {
                _durationSeconds.value = 0L
                while (true) {
                    delay(1000L)
                    _durationSeconds.value += 1L
                }
            }
        }
    }

    fun toggleMute() {
        webRtcEngine?.toggleMicMute()
    }

    fun toggleCamera() {
        val current = _webRtcState.value.isCameraEnabled
        webRtcEngine?.enableCamera(!current)
    }

    fun switchCamera() {
        webRtcEngine?.switchCamera()
    }

    fun setAudioOutput(route: AudioOutputRoute) {
        webRtcEngine?.applyAudioOutputRoute(route)
    }

    fun startScreenShare(resultCode: Int, data: Intent, audioSource: ScreenAudioSource) {
        val session = _activeCall.value ?: return
        CallForegroundService.startForScreenShare(
            context = appContext,
            title = "Talkback — Compartilhamento de tela",
            resultCode = resultCode,
            data = data
        )
        viewModelScope.launch {
            // Brief wait so Foreground Service initializes MediaProjection instance on Android 14+
            delay(250L)
            webRtcEngine?.startScreenShare(
                permissionResultIntent = data,
                audioSource = audioSource,
                mediaProjection = CallForegroundService.activeMediaProjection
            )
            callRepo.updateScreenSharing(session.id, currentUserId)
        }
    }

    fun stopScreenShare() {
        val session = _activeCall.value ?: return
        webRtcEngine?.stopScreenShare()
        viewModelScope.launch {
            callRepo.updateScreenSharing(session.id, null)
            CallForegroundService.startForCall(appContext, session.callerName)
        }
    }

    private fun cleanupCallResources() {
        timerJob?.cancel()
        timerJob = null
        signalJob?.cancel()
        signalJob = null
        callSessionJob?.cancel()
        callSessionJob = null
        stateForwardJob?.cancel()
        stateForwardJob = null
        webRtcEngine?.release()
        webRtcEngine = null
        CallForegroundService.stop(appContext)
        _activeCall.value = null
        _durationSeconds.value = 0L
        _webRtcState.value = WebRtcRuntimeState()
    }

    override fun onCleared() {
        cleanupCallResources()
        super.onCleared()
    }
}
