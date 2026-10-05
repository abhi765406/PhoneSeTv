package com.homewatch.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.webrtc.PeerConnection;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

public class CameraModeActivity extends Activity implements CameraForegroundService.StatusListener {

    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String EXTRA_DISPLAY_NAME = "display_name";

    private Button muteMicButton;
    
private boolean micMuted = false;
    private SurfaceViewRenderer localRenderer;
    private TextView statusText;
    private TextView roomCodeText;
    private TextView motionCountText;
    private Button armToggleButton;
    private SeekBar sensitivitySeekBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RtcClient rendererOwner; // used only to call initRenderer with the service's EGL context
    private String roomCode;
    private boolean armed = false;
    private int motionCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_mode);

        roomCode = getIntent().getStringExtra(EXTRA_ROOM_CODE);
        String displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);

        localRenderer = findViewById(R.id.localRenderer);
        statusText = findViewById(R.id.statusText);
        roomCodeText = findViewById(R.id.roomCodeText);
        motionCountText = findViewById(R.id.motionCountText);
        armToggleButton = findViewById(R.id.armToggleButton);
        muteMicButton = findViewById(R.id.muteMicButton);
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar);
        

        roomCodeText.setText("Room code: " + roomCode);

        SharedPreferences prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        armed = prefs.getBoolean("armed", false);
        sensitivitySeekBar.setMax(9);
        sensitivitySeekBar.setProgress(prefs.getInt("sensitivity", 5) - 1);
        updateArmButton();

        armToggleButton.setOnClickListener(v -> toggleArmed());
        muteMicButton.setOnClickListener(v -> toggleMicMute());
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit()
                            .putInt("sensitivity", progress + 1).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        CameraForegroundService.setListener(this);

        Intent serviceIntent = new Intent(this, CameraForegroundService.class);
        serviceIntent.putExtra(CameraForegroundService.EXTRA_ROOM_CODE, roomCode);
        serviceIntent.putExtra(CameraForegroundService.EXTRA_DISPLAY_NAME, displayName);
        startForegroundServiceCompat(serviceIntent);
    }

    private void startForegroundServiceCompat(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void toggleArmed() {
        armed = !armed;
        Intent intent = new Intent(this, CameraForegroundService.class);
        intent.setAction(CameraForegroundService.ACTION_SET_ARMED);
        intent.putExtra(CameraForegroundService.EXTRA_ARMED, armed);
        startService(intent);
        updateArmButton();
    }

    private void updateArmButton() {
        armToggleButton.setText(armed ? "Disarm" : "Arm (enable motion alerts)");
    }

    @Override
    protected void onResume() {
        super.onResume();
        CameraForegroundService.setListener(this);
    }

    @Override
    protected void onPause() {
        // Deliberately NOT calling setListener(null) here - the service keeps running and
        // alerting even while this screen isn't visible, which is the whole point.
        super.onPause();
    }

    @Override
    public void onStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }

    @Override
    public void onArmedChanged(boolean armed) {
        this.armed = armed;
        mainHandler.post(this::updateArmButton);
    }

    @Override
    public void onMotionTriggered() {
        motionCount++;
        mainHandler.post(() -> {
            motionCountText.setText("Motion events this session: " + motionCount);
            Toast.makeText(this, "Motion detected - siren sounded, snapshot saved", Toast.LENGTH_SHORT).show();
        });

        private void toggleMicMute() {
    if (rendererOwner == null) {
        Toast.makeText(this, "Still starting up - try again in a moment", Toast.LENGTH_SHORT).show();
        return;
    }
    micMuted = !micMuted;
    rendererOwner.setMicEnabled(!micMuted);
    muteMicButton.setText(micMuted ? "Unmute This Phone's Mic" : "Mute This Phone's Mic");
}
    }

    @Override
    public void onPeerConnected() {
        mainHandler.post(() -> statusText.setText("Viewer connected ✓"));
    }

    @Override
    public void onPeerDisconnected() {
        mainHandler.post(() -> statusText.setText("Waiting for viewer…"));
    }

    @Override
    public void onLocalVideoReady(VideoTrack track, RtcClient client) {
        mainHandler.post(() -> {
            rendererOwner = client;
            client.initRenderer(localRenderer, false);
            track.addSink(localRenderer);
        });
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "Camera keeps running in the background. Use Stop on the notification to end it.", Toast.LENGTH_LONG).show();
        finish();
    }
}
