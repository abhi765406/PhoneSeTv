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

    private SurfaceViewRenderer localRenderer;
    private TextView statusText;
    private TextView roomCodeText;
    private TextView motionCountText;
    private Button armToggleButton;
    private Button muteMicButton;
    private Button switchCameraButton;
    private SeekBar sensitivitySeekBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RtcClient rendererOwner; // also used to control this phone's own mic + camera
    private String roomCode;
    private boolean armed = false;
    private boolean micMuted = false;
    private boolean isFrontCamera = false; // CameraForegroundService starts on the back camera
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
        switchCameraButton = findViewById(R.id.switchCameraButton);
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar);

        roomCodeText.setText("Room code: " + roomCode);

        SharedPreferences prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        armed = prefs.getBoolean("armed", false);
        sensitivitySeekBar.setMax(9);
        sensitivitySeekBar.setProgress(prefs.getInt("sensitivity", 5) - 1);
        updateArmButton();

        armToggleButton.setOnClickListener(v -> toggleArmed());
        muteMicButton.setOnClickListener(v -> toggleMicMute());
        switchCameraButton.setOnClickListener(v -> onSwitchCameraClicked());
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

    private void toggleMicMute() {
        boolean newMuted = !micMuted;
        Intent intent = new Intent(this, CameraForegroundService.class);
        intent.setAction(CameraForegroundService.ACTION_SET_MUTED);
        intent.putExtra(CameraForegroundService.EXTRA_MUTED, newMuted);
        startService(intent);
        // Don't flip micMuted or the button text here - wait for onMicMuteChanged from the
        // service, so this screen always reflects the real state even if a remote mute
        // command arrives at the same moment.
    }

    private void onSwitchCameraClicked() {
        if (rendererOwner == null) {
            Toast.makeText(this, "Still starting up - try again in a moment", Toast.LENGTH_SHORT).show();
            return;
        }
        switchCameraButton.setEnabled(false);
        rendererOwner.switchCamera(new RtcClient.CameraSwitchListener() {
            @Override
            public void onSwitched(boolean isFrontCameraNow) {
                mainHandler.post(() -> {
                    isFrontCamera = isFrontCameraNow;
                    localRenderer.setMirror(isFrontCameraNow); // selfie-style mirror only makes sense on the front camera
                    switchCameraButton.setText(isFrontCameraNow
                            ? "🔄 Switch to Back Camera"
                            : "🔄 Switch to Front Camera");
                    switchCameraButton.setEnabled(true);
                });
            }

            @Override
            public void onSwitchFailed(String error) {
                mainHandler.post(() -> {
                    Toast.makeText(CameraModeActivity.this, "Couldn't switch camera: " + error, Toast.LENGTH_SHORT).show();
                    switchCameraButton.setEnabled(true);
                });
            }
        });
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
    }

    @Override
    public void onMicMuteChanged(boolean muted) {
        micMuted = muted;
        mainHandler.post(() -> muteMicButton.setText(
                muted ? "Unmute This Phone's Mic" : "Mute This Phone's Mic"));
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
