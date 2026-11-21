package com.example.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioProcessingSettings
import com.example.data.model.CallSignal
import com.example.data.model.ScreenAudioSource
import com.example.data.model.SfuConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap

data class WebRtcRuntimeState(
    val isConnected: Boolean = false,
    val connectionStateLabel: String = "Aguardando conexão",
    val isMicMuted: Boolean = false,
    val isCameraEnabled: Boolean = false,
    val isUsingFrontCamera: Boolean = true,
    val isScreenSharing: Boolean = false,
    val screenAudioSource: ScreenAudioSource = ScreenAudioSource.NONE,
    val audioOutput: AudioOutputRoute = AudioOutputRoute.SPEAKER,
    val sfuConnected: Boolean = false,
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTracks: Map<String, VideoTrack> = emptyMap()
)

class WebRtcCallEngine(
    private val context: Context,
    private val onSendSignal: (toUid: String, type: String, sdp: String?, ice: String?, sdpMid: String?, sdpMLineIndex: Long?) -> Unit,
    private val onScreenShareStopped: () -> Unit
) {
    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingSignals = ConcurrentHashMap<String, Boolean>()

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenVideoSource: VideoSource? = null
    private var screenVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenTextureHelper: SurfaceTextureHelper? = null

    private var internalAudioRecord: AudioRecord? = null
    private var sfuWebSocket: WebSocket? = null
    private val okHttp = OkHttpClient()

    private val _state = MutableStateFlow(WebRtcRuntimeState())
    val state: StateFlow<WebRtcRuntimeState> = _state.asStateFlow()

    private var audioSettings = AudioProcessingSettings()
    private var sfuConfig = SfuConfiguration()

    fun initialize(settings: AudioProcessingSettings, sfu: SfuConfiguration) {
        audioSettings = settings
        sfuConfig = sfu
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(settings.echoCancellation)
            .setUseHardwareNoiseSuppressor(settings.noiseSuppression || settings.backgroundNoiseReduction)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", settings.echoCancellation.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", settings.noiseSuppression.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", settings.autoGainControl.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", settings.backgroundNoiseReduction.toString()))
        }

        localAudioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("talkback_audio_0", localAudioSource)
        localAudioTrack?.setEnabled(true)

        applyAudioOutputRoute(settings.preferredOutput)
        connectToSfuIfConfigured(sfu)
    }

    private fun connectToSfuIfConfigured(sfu: SfuConfiguration) {
        if (!sfu.isSfuConfigured) {
            _state.value = _state.value.copy(sfuConnected = false)
            return
        }
        try {
            val request = Request.Builder().url(sfu.signalingUrl).build()
            sfuWebSocket = okHttp.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = _state.value.copy(sfuConnected = true)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w("TalkbackSFU", "SFU connection failed: ${t.message}")
                    _state.value = _state.value.copy(sfuConnected = false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.value = _state.value.copy(sfuConnected = false)
                }
            })
        } catch (e: Exception) {
            Log.w("TalkbackSFU", "Invalid SFU URL: ${e.message}")
            _state.value = _state.value.copy(sfuConnected = false)
        }
    }

    fun startLocalMedia(enableVideo: Boolean) {
        if (factory == null) initialize(audioSettings, sfuConfig)
        if (enableVideo) {
            enableCamera(true)
        }
    }

    fun toggleMicMute(): Boolean {
        val next = !_state.value.isMicMuted
        localAudioTrack?.setEnabled(!next)
        _state.value = _state.value.copy(isMicMuted = next)
        return next
    }

    fun enableCamera(enable: Boolean) {
        if (enable == _state.value.isCameraEnabled) return
        if (enable) {
            val f = factory ?: return
            val enumerator = Camera2Enumerator(appContext)
            val frontDevice = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (frontDevice == null) {
                Log.w("TalkbackWebRTC", "Nenhuma câmera disponível no dispositivo.")
                return
            }
            val capturer = enumerator.createCapturer(frontDevice, null) ?: return
            cameraCapturer = capturer
            surfaceTextureHelper = SurfaceTextureHelper.create("TalkbackCamThread", eglBase.eglBaseContext)
            val source = f.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, appContext, source.capturerObserver)
            capturer.startCapture(720, 1280, 30)

            val track = f.createVideoTrack("talkback_video_0", source)
            track.setEnabled(true)
            localVideoSource = source
            localVideoTrack = track

            peerConnections.values.forEach { pc ->
                pc.addTrack(track, listOf("talkback_stream"))
            }
            _state.value = _state.value.copy(
                isCameraEnabled = true,
                isUsingFrontCamera = enumerator.isFrontFacing(frontDevice),
                localVideoTrack = track
            )
        } else {
            runCatching { cameraCapturer?.stopCapture() }
            cameraCapturer?.dispose()
            cameraCapturer = null
            localVideoTrack?.setEnabled(false)
            localVideoTrack = null
            localVideoSource?.dispose()
            localVideoSource = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            _state.value = _state.value.copy(isCameraEnabled = false, localVideoTrack = null)
        }
    }

    fun switchCamera() {
        cameraCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _state.value = _state.value.copy(isUsingFrontCamera = isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w("TalkbackWebRTC", "Camera switch error: $errorDescription")
            }
        })
    }

    fun startScreenShare(
        permissionResultIntent: Intent,
        audioSource: ScreenAudioSource,
        mediaProjection: MediaProjection?
    ) {
        val f = factory ?: return
        stopScreenShareInternal(notify = false)
        try {
            val capturer = ScreenCapturerAndroid(
                permissionResultIntent,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        engineScope.launch {
                            stopScreenShareInternal(notify = true)
                        }
                    }
                }
            )
            screenCapturer = capturer
            screenTextureHelper = SurfaceTextureHelper.create("TalkbackScreenThread", eglBase.eglBaseContext)
            val source = f.createVideoSource(true)
            capturer.initialize(screenTextureHelper, appContext, source.capturerObserver)
            capturer.startCapture(720, 1280, 24)

            val track = f.createVideoTrack("talkback_screen_0", source)
            track.setEnabled(true)
            screenVideoSource = source
            screenVideoTrack = track

            peerConnections.values.forEach { pc ->
                pc.addTrack(track, listOf("talkback_screen_stream"))
            }

            if ((audioSource == ScreenAudioSource.INTERNAL_AUDIO || audioSource == ScreenAudioSource.BOTH) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                mediaProjection != null
            ) {
                startInternalAudioPlaybackCapture(mediaProjection)
            }

            val micEnabled = audioSource == ScreenAudioSource.MICROPHONE || audioSource == ScreenAudioSource.BOTH
            if (audioSource != ScreenAudioSource.NONE) {
                localAudioTrack?.setEnabled(micEnabled && !_state.value.isMicMuted)
            }

            _state.value = _state.value.copy(
                isScreenSharing = true,
                screenAudioSource = audioSource,
                localVideoTrack = track
            )
        } catch (e: Exception) {
            Log.e("TalkbackScreenShare", "Failed to start ScreenCapturerAndroid", e)
            stopScreenShareInternal(notify = true)
        }
    }

    private fun startInternalAudioPlaybackCapture(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            internalAudioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf.coerceAtLeast(4096))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            internalAudioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.w("TalkbackAudioCapture", "AudioPlaybackCapture denied: ${e.message}")
        } catch (e: Exception) {
            Log.w("TalkbackAudioCapture", "AudioPlaybackCapture unavailable: ${e.message}")
        }
    }

    fun stopScreenShare() {
        stopScreenShareInternal(notify = true)
    }

    private fun stopScreenShareInternal(notify: Boolean) {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        screenCapturer = null
        screenVideoTrack?.setEnabled(false)
        screenVideoTrack = null
        screenVideoSource?.dispose()
        screenVideoSource = null
        screenTextureHelper?.dispose()
        screenTextureHelper = null
        runCatching {
            internalAudioRecord?.stop()
            internalAudioRecord?.release()
        }
        internalAudioRecord = null
        _state.value = _state.value.copy(
            isScreenSharing = false,
            screenAudioSource = ScreenAudioSource.NONE,
            localVideoTrack = localVideoTrack
        )
        if (notify) onScreenShareStopped()
    }

    fun applyAudioOutputRoute(route: AudioOutputRoute) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = when (route) {
                AudioOutputRoute.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioOutputRoute.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                AudioOutputRoute.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            val device = am.availableCommunicationDevices.firstOrNull { it.type == targetType }
            if (device != null) {
                am.setCommunicationDevice(device)
            } else {
                am.isSpeakerphoneOn = route == AudioOutputRoute.SPEAKER
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = route == AudioOutputRoute.SPEAKER
        }
        _state.value = _state.value.copy(audioOutput = route)
    }

    fun createOfferForPeer(peerUid: String) {
        val pc = getOrCreatePeerConnection(peerUid) ?: return
        pc.createOffer(object : SdpObserverAdapter("CreateOffer") {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SdpObserverAdapter("SetLocalOffer"), desc)
                onSendSignal(peerUid, "offer", desc.description, null, null, null)
            }
        }, MediaConstraints())
    }

    fun handleIncomingSignal(signal: CallSignal) {
        if (pendingSignals.putIfAbsent(signal.id, true) != null) return
        val pc = getOrCreatePeerConnection(signal.fromUid) ?: return
        when (signal.type) {
            "offer" -> {
                val sdp = signal.sdp ?: return
                pc.setRemoteDescription(
                    SdpObserverAdapter("SetRemoteOffer"),
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
                pc.createAnswer(object : SdpObserverAdapter("CreateAnswer") {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        pc.setLocalDescription(SdpObserverAdapter("SetLocalAnswer"), desc)
                        onSendSignal(signal.fromUid, "answer", desc.description, null, null, null)
                    }
                }, MediaConstraints())
            }
            "answer" -> {
                val sdp = signal.sdp ?: return
                pc.setRemoteDescription(
                    SdpObserverAdapter("SetRemoteAnswer"),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
            }
            "ice" -> {
                val cand = signal.iceCandidate ?: return
                val mid = signal.sdpMid ?: "0"
                val mLine = (signal.sdpMLineIndex ?: 0L).toInt()
                pc.addIceCandidate(IceCandidate(mid, mLine, cand))
            }
        }
    }

    private fun getOrCreatePeerConnection(peerUid: String): PeerConnection? {
        peerConnections[peerUid]?.let { return it }
        val f = factory ?: return null

        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        if (sfuConfig.turnServerUrl.isNotBlank()) {
            iceServers.add(
                PeerConnection.IceServer.builder(sfuConfig.turnServerUrl)
                    .setUsername(sfuConfig.turnUsername)
                    .setPassword(sfuConfig.turnCredential)
                    .createIceServer()
            )
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                val connected = newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
                val label = when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> "Conectado"
                    PeerConnection.IceConnectionState.CHECKING -> "Conectando mídia..."
                    PeerConnection.IceConnectionState.DISCONNECTED -> "Reconectando..."
                    PeerConnection.IceConnectionState.FAILED -> "Falha na conexão ICE"
                    else -> "Aguardando par"
                }
                _state.value = _state.value.copy(isConnected = connected, connectionStateLabel = label)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                onSendSignal(
                    peerUid,
                    "ice",
                    null,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex.toLong()
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack && track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val updated = _state.value.remoteVideoTracks.toMutableMap()
                    updated[peerUid] = track
                    _state.value = _state.value.copy(remoteVideoTracks = updated)
                }
            }
        }) ?: return null

        localAudioTrack?.let { pc.addTrack(it, listOf("talkback_stream")) }
        localVideoTrack?.let { pc.addTrack(it, listOf("talkback_stream")) }
        screenVideoTrack?.let { pc.addTrack(it, listOf("talkback_screen_stream")) }

        peerConnections[peerUid] = pc
        return pc
    }

    fun release() {
        stopScreenShareInternal(notify = false)
        enableCamera(false)
        peerConnections.values.forEach { runCatching { it.close(); it.dispose() } }
        peerConnections.clear()
        pendingSignals.clear()
        runCatching { sfuWebSocket?.close(1000, "Call ended") }
        sfuWebSocket = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        factory?.dispose()
        factory = null
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
        }
        _state.value = WebRtcRuntimeState()
    }
}

private open class SdpObserverAdapter(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.w("TalkbackWebRTC", "$tag create failure: $error")
    }

    override fun onSetFailure(error: String?) {
        Log.w("TalkbackWebRTC", "$tag set failure: $error")
    }
}
