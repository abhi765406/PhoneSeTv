package com.homewatch.app;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ViewerActivity extends Activity {

    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String EXTRA_DISPLAY_NAME = "display_name";

    private static final String TAG = "ViewerActivity";

    private SurfaceViewRenderer remoteRenderer;
    private TextView statusText;
    private TextView motionBanner;
    private Button sirenButton;
    private Button talkButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RtcClient rtcClient;
    private SignalingClient signalingClient;
    private VideoTrack remoteVideoTrack;

    private boolean isInitiator = false;
    private boolean micOn = false;
    private String roomCode;
    private String displayName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        roomCode = getIntent().getStringExtra(EXTRA_ROOM_CODE);
        displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);

        remoteRenderer = findViewById(R.id.remoteRenderer);
        statusText = findViewById(R.id.statusText);
        motionBanner = findViewById(R.id.motionBanner);
        sirenButton = findViewById(R.id.sirenButton);
        talkButton = findViewById(R.id.talkButton);

        sirenButton.setOnClickListener(v -> onSirenClicked());
        talkButton.setOnClickListener(v -> onTalkToggled());

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setSpeakerphoneOn(true);

        rtcClient = new RtcClient(this, RtcClient.Role.VIEWER, rtcCallbacks);
        rtcClient.initRenderer(remoteRenderer, false);
        rtcClient.startMic();
        rtcClient.setMicEnabled(false); // muted until the user taps Talk

        statusText.setText("Connecting to camera…");
        new Thread(() -> {
            List<PeerConnection.IceServer> servers = IceServerFetcher.fetch(Config.ICE_SERVERS_URL);
            mainHandler.post(() -> startCall(servers));
        }).start();
    }

    private void startCall(List<PeerConnection.IceServer> iceServers) {
        rtcClient.createPeerConnection(iceServers);
        signalingClient = new SignalingClient(Config.SIGNALING_WS_URL, roomCode, displayName, signalingListener);
        signalingClient.connect();
    }

    private final SignalingClient.Listener signalingListener = new SignalingClient.Listener() {
        @Override public void onStatus(String message) {
            mainHandler.post(() -> statusText.setText(message));
        }

        @Override public void onJoined(String selfId, boolean initiator, String room) {
            isInitiator = initiator;
        }

        @Override public void onPeerJoined(String peerId, String peerName) {
            mainHandler.post(() -> statusText.setText("Camera found. Connecting video…"));
            if (isInitiator) {
                rtcClient.createOffer(new SdpObserverAdapter() {
                    @Override public void onCreateSuccess(SessionDescription sdp) {
                        rtcClient.setLocalDescription(new SdpObserverAdapter(), sdp);
                        sendSdp("offer", sdp);
                    }
                });
            }
        }

        @Override public void onPeerLeft() {
            mainHandler.post(() -> {
                statusText.setText("Camera disconnected");
                Toast.makeText(ViewerActivity.this, "The camera phone disconnected", Toast.LENGTH_LONG).show();
            });
        }

        @Override public void onRoomFull() {
            mainHandler.post(() -> {
                statusText.setText("This room already has a viewer connected");
                Toast.makeText(ViewerActivity.this, "Someone else is already viewing this camera", Toast.LENGTH_LONG).show();
            });
        }

        @Override public void onSignal(JSONObject message) {
            handleSignal(message);
        }

        @Override public void onChat(String from, String text) {
        }

        @Override public void onMotionAlert(long timestamp) {
            mainHandler.post(() -> showMotionBanner(timestamp));
        }

        @Override public void onSirenCommand() {
        }

        @Override public void onServerError(String message) {
            mainHandler.post(() -> statusText.setText("Server error: " + message));
        }
    };

    private void handleSignal(JSONObject message) {
        try {
            String type = message.optString("type", "");
            if ("offer".equals(type)) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.OFFER, message.getString("sdp"));
                rtcClient.setRemoteDescription(new SdpObserverAdapter() {
                    @Override public void onSetSuccess() {
                        rtcClient.createAnswer(new SdpObserverAdapter() {
                            @Override public void onCreateSuccess(SessionDescription answerSdp) {
                                rtcClient.setLocalDescription(new SdpObserverAdapter(), answerSdp);
                                sendSdp("answer", answerSdp);
                            }
                        });
                    }
                }, sdp);
            } else if ("answer".equals(type)) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.ANSWER, message.getString("sdp"));
                rtcClient.setRemoteDescription(new SdpObserverAdapter(), sdp);
            } else if ("candidate".equals(type)) {
                IceCandidate candidate = new IceCandidate(
                        message.getString("sdpMid"),
                        message.getInt("sdpMLineIndex"),
                        message.getString("candidate"));
                rtcClient.addIceCandidate(candidate);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Bad signal message", e);
        }
    }

    private void sendSdp(String type, SessionDescription sdp) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", type);
            msg.put("sdp", sdp.description);
            signalingClient.send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "sendSdp failed", e);
        }
    }

    private final RtcClient.Callbacks rtcCallbacks = new RtcClient.Callbacks() {
        @Override public void onLocalIceCandidate(IceCandidate candidate) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "candidate");
                msg.put("sdpMid", candidate.sdpMid);
                msg.put("sdpMLineIndex", candidate.sdpMLineIndex);
                msg.put("candidate", candidate.sdp);
                signalingClient.send(msg);
            } catch (JSONException e) {
                Log.e(TAG, "candidate send failed", e);
            }
        }

        @Override public void onRemoteVideoTrack(VideoTrack track) {
            mainHandler.post(() -> {
                remoteVideoTrack = track;
                track.addSink(remoteRenderer);
                statusText.setText("Live");
            });
        }

        @Override public void onRemoteAudioAvailable() {
        }

        @Override public void onConnectionStateChanged(PeerConnection.PeerConnectionState state) {
            mainHandler.post(() -> {
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    statusText.setText("Live");
                } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED
                        || state == PeerConnection.PeerConnectionState.FAILED) {
                    statusText.setText("Connection lost - reconnecting…");
                }
            });
        }
    };

    private void showMotionBanner(long timestamp) {
        String time = new SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new Date(timestamp));
        motionBanner.setText("⚠ Motion detected at " + time);
        motionBanner.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(() -> motionBanner.setVisibility(View.GONE), 6000);
    }

    private void onSirenClicked() {
        if (signalingClient != null) {
            signalingClient.sendSirenCommand();
            Toast.makeText(this, "Siren signal sent to camera phone", Toast.LENGTH_SHORT).show();
        }
    }

    private void onTalkToggled() {
        micOn = !micOn;
        rtcClient.setMicEnabled(micOn);
        talkButton.setText(micOn ? "Stop Talking" : "Talk");
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (signalingClient != null) signalingClient.leaveAndClose();
        if (rtcClient != null) rtcClient.close();
        super.onDestroy();
    }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { Log.e("SdpObserver", "create failed: " + error); }
        @Override public void onSetFailure(String error) { Log.e("SdpObserver", "set failed: " + error); }
    }
}
