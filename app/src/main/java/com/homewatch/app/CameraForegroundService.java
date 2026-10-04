package com.homewatch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CameraForegroundService extends Service {

    private static final String TAG = "CameraForegroundSvc";
    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String ACTION_STOP = "com.homewatch.app.STOP_CAMERA";
    public static final String ACTION_SET_ARMED = "com.homewatch.app.SET_ARMED";
    public static final String EXTRA_ARMED = "armed";

    private static final String CHANNEL_ID = "homewatch_camera_channel";
    private static final int NOTIF_ID = 10;

    public interface StatusListener {
        void onStatus(String message);
        void onArmedChanged(boolean armed);
        void onMotionTriggered();
        void onPeerConnected();
        void onPeerDisconnected();
        void onLocalVideoReady(VideoTrack track, RtcClient client);
    }

    private static StatusListener listener;

    public static void setListener(StatusListener l) {
        listener = l;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RtcClient rtcClient;
    private SignalingClient signalingClient;
    private MotionDetector motionDetector;
    private SnapshotSaver snapshotSaver;
    private MediaPlayer sirenPlayer;
    private PowerManager.WakeLock wakeLock;

    private String roomCode;
    private String displayName;
    private boolean armed = false;
    private boolean isInitiator = false;
    private boolean started = false;
    private List<PeerConnection.IceServer> iceServers;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SET_ARMED.equals(intent.getAction())) {
            armed = intent.getBooleanExtra(EXTRA_ARMED, false);
            if (motionDetector != null) motionDetector.setEnabled(armed);
            if (listener != null) listener.onArmedChanged(armed);
            getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit().putBoolean("armed", armed).apply();
            return START_STICKY;
        }

        if (!started && intent != null) {
            started = true;
            roomCode = intent.getStringExtra(EXTRA_ROOM_CODE);
            displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
            startForegroundNotification("Starting camera…");
            acquireWakeLock();
            setupAudioRouting();
            fetchIceServersAndStart();
        }
        return START_STICKY;
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeWatch::CameraWakeLock");
        wakeLock.acquire(12 * 60 * 60 * 1000L); // 12h safety cap, renewed implicitly by reconnect logic staying alive
    }

    private void setupAudioRouting() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setSpeakerphoneOn(true); // so a viewer's two-way talk is audible near the camera, not just through the earpiece
    }

    private void fetchIceServersAndStart() {
        new Thread(() -> {
            List<PeerConnection.IceServer> servers = IceServerFetcher.fetch(Config.ICE_SERVERS_URL);
            iceServers = servers;
            mainHandler.post(this::startStreaming);
        }).start();
    }

    private void startStreaming() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        int sensitivity = prefs.getInt("sensitivity", 5);

        rtcClient = new RtcClient(this, RtcClient.Role.CAMERA, rtcCallbacks);
        motionDetector = new MotionDetector(this::onMotionDetected, sensitivity);
        snapshotSaver = new SnapshotSaver(this);

        VideoTrack localTrack = rtcClient.startCamera(false, motionDetector);
        localTrack.addSink(snapshotSaver);
        rtcClient.startMic();
        rtcClient.createPeerConnection(iceServers);

        if (listener != null) listener.onLocalVideoReady(localTrack, rtcClient);

        signalingClient = new SignalingClient(Config.SIGNALING_WS_URL, roomCode, displayName, signalingListener);
        signalingClient.connect();
    }

    private final SignalingClient.Listener signalingListener = new SignalingClient.Listener() {
        @Override public void onStatus(String message) {
            notifyStatus(message);
        }

        @Override public void onJoined(String selfId, boolean initiator, String room) {
            isInitiator = initiator;
            notifyStatus("Waiting for your phone to connect…");
        }

        @Override public void onPeerJoined(String peerId, String peerName) {
            notifyStatus("Viewer connected. Setting up stream…");
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
            notifyStatus("Viewer disconnected. Waiting for reconnection…");
            if (listener != null) listener.onPeerDisconnected();
        }

        @Override public void onRoomFull() {
            notifyStatus("This room code is already in use elsewhere");
        }

        @Override public void onSignal(JSONObject message) {
            handleSignal(message);
        }

        @Override public void onChat(String from, String text) {
        }

        @Override public void onMotionAlert(long timestamp) {
        }

        @Override public void onSirenCommand() {
            playSiren();
        }

        @Override public void onServerError(String message) {
            notifyStatus("Server error: " + message);
        }
    };

    private void handleSignal(JSONObject message) {
        try {
            String type = message.optString("type", "");
            if ("answer".equals(type)) {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.ANSWER, message.getString("sdp"));
                rtcClient.setRemoteDescription(new SdpObserverAdapter(), sdp);
            } else if ("offer".equals(type)) {
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
            // Camera role doesn't expect incoming video.
        }

        @Override public void onRemoteAudioAvailable() {
            notifyStatus("Two-way audio connected");
        }

        @Override public void onConnectionStateChanged(PeerConnection.PeerConnectionState state) {
            mainHandler.post(() -> {
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    notifyStatus("Connected ✓");
                    if (listener != null) listener.onPeerConnected();
                } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED
                        || state == PeerConnection.PeerConnectionState.FAILED) {
                    notifyStatus("Connection lost");
                    if (listener != null) listener.onPeerDisconnected();
                }
            });
        }
    };

    private void onMotionDetected() {
        mainHandler.post(() -> {
            if (listener != null) listener.onMotionTriggered();
            if (snapshotSaver != null) snapshotSaver.requestCapture();
            playSiren();
            if (signalingClient != null) signalingClient.sendMotionAlert();
            String time = new SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new Date());
            updateNotification("⚠ Motion detected at " + time);
        });
    }

    private void playSiren() {
        try {
            if (sirenPlayer != null) {
                sirenPlayer.release();
                sirenPlayer = null;
            }
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            sirenPlayer = MediaPlayer.create(this, alarmUri);
            if (sirenPlayer == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                sirenPlayer = MediaPlayer.create(this, alarmUri);
            }
            if (sirenPlayer != null) {
                sirenPlayer.setLooping(false);
                sirenPlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Siren playback failed", e);
        }
    }

    private void notifyStatus(String message) {
        mainHandler.post(() -> {
            if (listener != null) listener.onStatus(message);
        });
        updateNotification(message);
    }

    private void startForegroundNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HomeWatch Camera", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        startForeground(NOTIF_ID, buildNotification(text));
    }

    private void updateNotification(String text) {
        if (!started) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, CameraForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("HomeWatch camera active - room " + roomCode)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending).build());
        return builder.build();
    }

    private void stopEverything() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sirenPlayer != null) {
            sirenPlayer.release();
            sirenPlayer = null;
        }
        if (signalingClient != null) signalingClient.leaveAndClose();
        if (rtcClient != null) rtcClient.close();
        started = false;
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** SdpObserver has four methods; most callers only care about one or two. */
    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { Log.e("SdpObserver", "create failed: " + error); }
        @Override public void onSetFailure(String error) { Log.e("SdpObserver", "set failed: " + error); }
    }
}
