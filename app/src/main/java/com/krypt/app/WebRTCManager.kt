package com.krypt.app

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnection.IceServer

class WebRTCManager(
    private val context: Context,
    private val localUuid: String,
    private val remoteUuid: String,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onLocalSdp: (SessionDescription) -> Unit,
    private val onRemoteTrack: (VideoTrack) -> Unit,
    private val onCallEnded: () -> Unit
) {
    private val TAG = "WebRTCManager"

    private var eglBase: EglBase? = EglBase.create()
    val eglBaseContext: EglBase.Context? get() = eglBase?.eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    var remoteVideoTrack: VideoTrack? = null
        private set
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isDisposed = false

    init {
        try {
            initFactory()
            initLocalTracks()
            createPeerConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            safeDispose()
            onCallEnded()
        }
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val eglCtx = eglBase!!.eglBaseContext
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglCtx, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglCtx))
            .createPeerConnectionFactory()
    }

    private fun initLocalTracks() {
        val factory = peerConnectionFactory ?: return
        val eglCtx = eglBase?.eglBaseContext ?: return
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio_local", audioSource)
        videoCapturer = createCameraCapturer()
        videoCapturer?.let { capturer ->
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglCtx)
            val videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            localVideoTrack = factory.createVideoTrack("video_local", videoSource)
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames
            for (name in deviceNames) {
                if (enumerator.isFrontFacing(name)) {
                    enumerator.createCapturer(name, null)?.let { return it }
                }
            }
            for (name in deviceNames) {
                enumerator.createCapturer(name, null)?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Camera init failed", e)
            null
        }
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (!isDisposed) onIceCandidate(candidate)
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack && !isDisposed) {
                    remoteVideoTrack = track
                    onRemoteTrack(track)
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (!isDisposed && (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED)) {
                    onCallEnded()
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: run { onCallEnded(); return }

        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { if (!isDisposed) onLocalSdp(sdp) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer: $error") }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer(offerSdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                val constraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() { if (!isDisposed) onLocalSdp(sdp) }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, sdp)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) { Log.e(TAG, "createAnswer: $error") }
                    override fun onSetFailure(error: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Log.e(TAG, "setRemoteOffer: $p0") }
        }, offerSdp)
    }

    fun setRemoteAnswer(answerSdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Log.e(TAG, "setRemoteAnswer: $p0") }
        }, answerSdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (!isDisposed) peerConnection?.addIceCandidate(candidate)
    }

    fun attachLocalView(view: SurfaceViewRenderer) {
        try {
            val eglCtx = eglBase?.eglBaseContext ?: return
            view.init(eglCtx, null)
            view.setMirror(true)
            view.setEnableHardwareScaler(true)
            localVideoTrack?.addSink(view)
        } catch (e: Exception) { Log.e(TAG, "attachLocalView: $e") }
    }

    fun attachRemoteView(view: SurfaceViewRenderer) {
        try {
            val eglCtx = eglBase?.eglBaseContext ?: return
            view.init(eglCtx, null)
            view.setMirror(false)
            view.setEnableHardwareScaler(true)
            remoteVideoTrack?.addSink(view)
        } catch (e: Exception) { Log.e(TAG, "attachRemoteView: $e") }
    }

    fun toggleMute(mute: Boolean) { localAudioTrack?.setEnabled(!mute) }
    fun toggleCamera(off: Boolean) { localVideoTrack?.setEnabled(!off) }

    private fun safeDispose() {
        if (isDisposed) return
        isDisposed = true
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null; peerConnectionFactory = null; peerConnection = null
    }

    fun endCall() { safeDispose() }
}
