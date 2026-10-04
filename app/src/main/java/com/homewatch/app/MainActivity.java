package com.homewatch.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private EditText nameInput;
    private EditText roomInput;
    private Button generateCodeButton;
    private Button startCameraButton;
    private Button startViewerButton;

    private String pendingMode; // "camera" or "viewer" - set right before requesting permissions

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nameInput = findViewById(R.id.nameInput);
        roomInput = findViewById(R.id.roomInput);
        generateCodeButton = findViewById(R.id.generateCodeButton);
        startCameraButton = findViewById(R.id.startCameraButton);
        startViewerButton = findViewById(R.id.startViewerButton);

        SharedPreferences prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        nameInput.setText(prefs.getString("display_name", ""));
        String lastRoom = prefs.getString("room_code", "");
        if (!lastRoom.isEmpty()) roomInput.setText(lastRoom);

        generateCodeButton.setOnClickListener(v -> roomInput.setText(generateRoomCode()));
        startCameraButton.setOnClickListener(v -> onStartClicked("camera"));
        startViewerButton.setOnClickListener(v -> onStartClicked("viewer"));
    }

    private String generateRoomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void onStartClicked(String mode) {
        String name = nameInput.getText().toString().trim();
        String room = roomInput.getText().toString().trim().toUpperCase(Locale.ROOT);

        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (room.isEmpty()) {
            if ("camera".equals(mode)) {
                room = generateRoomCode();
                roomInput.setText(room);
                Toast.makeText(this, "Generated room code " + room + " - enter this on the viewer phone", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Enter the room code shown on the camera phone", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit()
                .putString("display_name", name)
                .putString("room_code", room)
                .apply();

        pendingMode = mode;
        if (!hasPermissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            return;
        }
        launch(mode, name, room);
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasPermissions() && pendingMode != null) {
                String name = nameInput.getText().toString().trim();
                String room = roomInput.getText().toString().trim().toUpperCase(Locale.ROOT);
                launch(pendingMode, name, room);
            } else {
                Toast.makeText(this, "Camera and microphone are needed for HomeWatch to work", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void launch(String mode, String name, String room) {
        if ("camera".equals(mode)) {
            Intent intent = new Intent(this, CameraModeActivity.class);
            intent.putExtra(CameraModeActivity.EXTRA_ROOM_CODE, room);
            intent.putExtra(CameraModeActivity.EXTRA_DISPLAY_NAME, name);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, ViewerActivity.class);
            intent.putExtra(ViewerActivity.EXTRA_ROOM_CODE, room);
            intent.putExtra(ViewerActivity.EXTRA_DISPLAY_NAME, name);
            startActivity(intent);
        }
    }
}
