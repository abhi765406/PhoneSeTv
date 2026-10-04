package com.homewatch.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Talks to the signaling server over WebSocket. Handles reconnecting with
 * backoff and tells the UI *why* it's still connecting (server waking up
 * from Render's free-tier sleep vs. genuinely unreachable vs. waiting for
 * the other phone) instead of just showing "Connecting..." forever, which
 * was the original bug.
 */
public class SignalingClient {

    private static final String TAG = "SignalingClient";
    private static final int[] RETRY_DELAYS_MS = {1000, 2000, 4000, 8000, 15000, 15000, 15000};

    public interface Listener {
        void onStatus(String message);
        void onJoined(String selfId, boolean initiator, String room);
        void onPeerJoined(String peerId, String peerName);
        void onPeerLeft();
        void onRoomFull();
        void onSignal(JSONObject message); // offer / answer / candidate
        void onChat(String from, String text);
        void onMotionAlert(long timestamp);
        void onSirenCommand();
        void onServerError(String message);
    }

    private final String serverUrl;
    private final String roomCode;
    private final String displayName;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;

    private WebSocket webSocket;
    private int retryAttempt = 0;
    private boolean stopped = false;
    private boolean everConnected = false;

    public SignalingClient(String serverUrl, String roomCode, String displayName, Listener listener) {
        this.serverUrl = serverUrl;
        this.roomCode = roomCode;
        this.displayName = displayName;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void connect() {
        stopped = false;
        openSocket();
    }

    private void openSocket() {
        if (stopped) return;
        notifyStatus(retryAttempt == 0 ? "Connecting to server…" : "Still trying to reach the server…");

        Request request = new Request.Builder().url(serverUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                retryAttempt = 0;
                everConnected = true;
                mainHandler.post(() -> notifyStatus("Connected. Joining room…"));
                sendJoin();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                mainHandler.post(() -> handleMessage(text));
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                mainHandler.post(() -> {
                    if (!stopped) scheduleReconnect();
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure: " + t.getMessage());
                mainHandler.post(() -> {
                    if (!stopped) scheduleReconnect();
                });
            }
        });
    }

    private void scheduleReconnect() {
        int delay = RETRY_DELAYS_MS[Math.min(retryAttempt, RETRY_DELAYS_MS.length - 1)];
        retryAttempt++;

        if (!everConnected && retryAttempt >= 3) {
            notifyStatus("Waking up the server (free hosting sleeps when idle) - this can take up to a minute…");
        } else {
            notifyStatus("Connection lost. Reconnecting…");
        }

        mainHandler.postDelayed(this::openSocket, delay);
    }

    private void sendJoin() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "join");
            msg.put("room", roomCode);
            msg.put("name", displayName);
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "sendJoin failed", e);
        }
    }

    public void send(JSONObject message) {
        if (webSocket != null) {
            webSocket.send(message.toString());
        }
    }

    public void sendChat(String text) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "chat");
            msg.put("text", text);
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "sendChat failed", e);
        }
    }

    public void sendMotionAlert() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "motion");
            msg.put("ts", System.currentTimeMillis());
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "sendMotionAlert failed", e);
        }
    }

    public void sendSirenCommand() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "siren");
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "sendSirenCommand failed", e);
        }
    }

    public void leaveAndClose() {
        stopped = true;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "leave");
            send(msg);
        } catch (JSONException ignored) {
        }
        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
    }

    private void handleMessage(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        String type = msg.optString("type", "");

        switch (type) {
            case "joined":
                notifyStatus("Waiting for your friend to join the room…");
                listener.onJoined(msg.optString("id"), msg.optBoolean("initiator", false), msg.optString("room"));
                break;
            case "peer-joined":
                notifyStatus("Friend found! Setting up the call…");
                listener.onPeerJoined(msg.optString("id"), msg.optString("name"));
                break;
            case "peer-left":
                listener.onPeerLeft();
                break;
            case "room-full":
                listener.onRoomFull();
                break;
            case "chat":
                listener.onChat(msg.optString("from"), msg.optString("text"));
                break;
            case "motion":
                long ts = msg.optLong("ts", 0L);
                listener.onMotionAlert(ts == 0L ? System.currentTimeMillis() : ts);
                break;
            case "siren":
                listener.onSirenCommand();
                break;
            case "error":
                listener.onServerError(msg.optString("message", "Server error"));
                break;
            case "offer":
            case "answer":
            case "candidate":
                listener.onSignal(msg);
                break;
            default:
                break;
        }
    }

    private void notifyStatus(String message) {
        listener.onStatus(message);
    }
}
