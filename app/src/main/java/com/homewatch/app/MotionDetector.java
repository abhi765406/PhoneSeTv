package com.homewatch.app;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;

/**
 * A simple, honest motion detector: downsamples each frame's brightness to a
 * coarse grid, compares it to the previous grid, and fires a callback when
 * enough of the grid changed at once. This is basic frame-difference
 * detection, not AI-based object recognition - it will trigger on things
 * like a light turning on or a curtain moving, not just people. It's meant
 * to be a useful alert, not a guarantee.
 */
public class MotionDetector implements VideoSink {

    public interface Listener {
        void onMotionDetected();
    }

    private static final int GRID_COLS = 16;
    private static final int GRID_ROWS = 12;
    private static final long MIN_FRAME_INTERVAL_MS = 250; // analyze at most 4x/sec - plenty for motion, light on CPU
    private static final long ALERT_COOLDOWN_MS = 8000; // don't re-fire constantly while motion continues

    private final Listener listener;
    private final int sensitivity; // 1 (least sensitive) .. 10 (most sensitive)

    private int[] previousGrid;
    private long lastAnalysisTime = 0;
    private long lastAlertTime = 0;
    private boolean enabled = false;

    public MotionDetector(Listener listener, int sensitivity) {
        this.listener = listener;
        this.sensitivity = Math.max(1, Math.min(10, sensitivity));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            previousGrid = null; // don't compare against a stale frame from before it was armed
        }
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        if (now - lastAnalysisTime < MIN_FRAME_INTERVAL_MS) return;
        lastAnalysisTime = now;

        try {
            VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
            int width = i420.getWidth();
            int height = i420.getHeight();
            ByteBuffer y = i420.getDataY().duplicate();
            int strideY = i420.getStrideY();

            int[] grid = new int[GRID_COLS * GRID_ROWS];
            for (int gy = 0; gy < GRID_ROWS; gy++) {
                int py = (gy * height) / GRID_ROWS;
                for (int gx = 0; gx < GRID_COLS; gx++) {
                    int px = (gx * width) / GRID_COLS;
                    int offset = py * strideY + px;
                    int brightness = y.get(offset) & 0xFF;
                    grid[gy * GRID_COLS + gx] = brightness;
                }
            }

            if (previousGrid != null) {
                int changedCells = 0;
                // Higher sensitivity = smaller brightness change counts as "changed".
                int threshold = 60 - (sensitivity * 5);
                for (int i = 0; i < grid.length; i++) {
                    if (Math.abs(grid[i] - previousGrid[i]) > threshold) {
                        changedCells++;
                    }
                }
                // Require a meaningful fraction of the frame to change, not just a few cells,
                // to avoid false triggers from sensor noise or tiny light flicker.
                int requiredCells = Math.max(3, (GRID_COLS * GRID_ROWS) / 10);
                if (changedCells >= requiredCells && now - lastAlertTime > ALERT_COOLDOWN_MS) {
                    lastAlertTime = now;
                    listener.onMotionDetected();
                }
            }
            previousGrid = grid;
        } catch (Exception ignored) {
            // Never let frame analysis crash the video pipeline.
        }
    }
}
