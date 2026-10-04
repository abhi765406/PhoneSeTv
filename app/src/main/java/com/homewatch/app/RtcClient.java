package com.homewatch.app;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wraps WebRTC for two roles sharing the same codebase:
 *  - CAMERA: captures the phone's camera + mic and streams them out.
 *  - VIEWER: captures only the mic (for two-way talk) and renders whatever
 *            video it receives.
 *
 * Capped resolution/bitrate so a stationary security camera doesn't burn
 * through a data plan if it's not on Wi-Fi.
 */
public class RtcClient {

    private static final String TAG = "RtcClient";
    private static final int CAM_WIDTH = 640;
    private static final int CAM_HEIGHT = 480;
    private static final int CAM_FPS = 15; // a security feed doesn't need 30fps - saves data and battery

    public enum Role { CAMERA, VIEWER }

    public interface Callbacks {
        void onLocalIceCandidate(IceCandidate candidate);
        void onRemoteVideoTrack(VideoTrack track);
        void onRemoteAudioAvailable();
        void onConnectionStateChanged(PeerConnection.PeerConnectionState state);
    }

    private final Context appContext;
    private final Role role;
    private final Callbacks callbacks;
    private final EglBase eglBase;

    private final PeerConnectionFactory factory;
    private PeerConnection peerConnection;

    private VideoCapturer cameraCapturer;
    private VideoSource cameraVideoSource;
    private SurfaceTextureHelper surfaceTextureHelper;

    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private AudioSource audioSource;

    public RtcClient(Context appContext, Role role, Callbacks callbacks) {
        this.appContext = appContext.getApplicationContext();
        this.role = role;
        this.callbacks = callbacks;
        this.eglBase = EglBase.create();

        PeerConnectionFactory.InitializationOptions initOptions =
                 PeerConnectionFactory.InitializationOptions.builder(this.appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        JavaAudioDeviceModule audioDeviceModule = JavaAudioDeviceModule.builder(this.appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory();
    }

    public EglBase.Context getEglContext() {
        return eglBase.getEglBaseContext();
    }

    public void initRenderer(SurfaceViewRenderer renderer, boolean mirror) {
        renderer.init(eglBase.getEglBaseContext(), null);
        renderer.setMirror(mirror);
        renderer.setEnableHardwareScaler(true);
    }

    /** CAMERA role only: starts the camera and returns its track so a local preview can show it. */
    public VideoTrack startCamera(boolean useFrontCamera, VideoSink extraSink) {
        Camera2Enumerator enumerator = new Camera2Enumerator(appContext);
        String cameraName = null;
        for (String name : enumerator.getDeviceNames()) {
            if (useFrontCamera && enumerator.isFrontFacing(name)) {
                cameraName = name;
                break;
            }
            if (!useFrontCamera && enumerator.isBackFacing(name)) {
                cameraName = name;
                break;
            }
        }
        if (cameraName == null) {
            String[] names = enumerator.getDeviceNames();
            if (names.length > 0) cameraName = names[0];
        }

        cameraCapturer = enumerator.createCapturer(cameraName, new CameraVideoCapturer.CameraEventsHandler() {
            @Override public void onCameraError(String error) { Log.e(TAG, "Camera error: " + error); }
            @Override public void onCameraDisconnected() {}
            @Override public void onCameraFreezed(String error) { Log.e(TAG, "Camera freeze: " + error); }
            @Override public void onCameraOpening(String cameraName) {}
            @Override public void onFirstFrameAvailable() {}
            @Override public void onCameraClosed() {}
        });

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        cameraVideoSource = factory.createVideoSource(false);
        cameraCapturer.initialize(surfaceTextureHelper, appContext, cameraVideoSource.getCapturerObserver());
        cameraCapturer.startCapture(CAM_WIDTH, CAM_HEIGHT, CAM_FPS);

        localVideoTrack = factory.createVideoTrack("video0", cameraVideoSource);
        localVideoTrack.setEnabled(true);
        if (extraSink != null) {
            localVideoTrack.addSink(extraSink); // motion detector taps the same frames the peer connection sends
        }
        return localVideoTrack;
    }

    /** Starts the mic. Both roles use this - camera for ambient sound, viewer for two-way talk. */
    public void startMic() {
        MediaConstraints audioConstraints = new MediaConstraints();
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);
    }

    public void createPeerConnection(List<PeerConnection.IceServer> iceServers) {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState newState) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {}
            @Override public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {}
            @Override public void onIceCandidate(IceCandidate candidate) {
                callbacks.onLocalIceCandidate(candidate);
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {}
            @Override public void onTrack(RtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack) {
                    callbacks.onRemoteVideoTrack((VideoTrack) track);
                } else if (track instanceof AudioTrack) {
                    callbacks.onRemoteAudioAvailable();
                }
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                callbacks.onConnectionStateChanged(newState);
            }
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {}
            @Override public void onIceCandidateError(org.webrtc.IceCandidateErrorEvent event) {}
            @Override public void onSelectedCandidatePairChanged(org.webrtc.CandidatePairChangeEvent event) {}
            @Override public void onRemoveTrack(RtpReceiver receiver) {}
        });

        List<String> streamIds = Collections.singletonList("homewatch-stream");
        if (localAudioTrack != null) {
            peerConnection.addTrack(localAudioTrack, streamIds);
        }
        if (role == Role.CAMERA && localVideoTrack != null) {
            peerConnection.addTrack(localVideoTrack, streamIds);
        }
    }

    public void createOffer(SdpObserver observer) {
        peerConnection.createOffer(observer, new MediaConstraints());
    }

    public void createAnswer(SdpObserver observer) {
        peerConnection.createAnswer(observer, new MediaConstraints());
    }

    public void setLocalDescription(SdpObserver observer, SessionDescription sdp) {
        peerConnection.setLocalDescription(observer, sdp);
    }

    public void setRemoteDescription(SdpObserver observer, SessionDescription sdp) {
        peerConnection.setRemoteDescription(observer, sdp);
    }

    public void addIceCandidate(IceCandidate candidate) {
        if (peerConnection != null) {
            peerConnection.addIceCandidate(candidate);
        }
    }

    public void setMicEnabled(boolean enabled) {
        if (localAudioTrack != null) localAudioTrack.setEnabled(enabled);
    }

    public void close() {
        try {
            if (cameraCapturer != null) {
                cameraCapturer.stopCapture();
                cameraCapturer.dispose();
            }
        } catch (InterruptedException ignored) {
        }
        if (localVideoTrack != null) localVideoTrack.dispose();
        if (localAudioTrack != null) localAudioTrack.dispose();
        if (cameraVideoSource != null) cameraVideoSource.dispose();
        if (peerConnection != null) peerConnection.close();
        if (surfaceTextureHelper != null) surfaceTextureHelper.dispose();
        eglBase.release();
    }

    static List<PeerConnection.IceServer> defaultStunOnly() {
        List<PeerConnection.IceServer> list = new ArrayList<>();
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        return list;
    }
}
