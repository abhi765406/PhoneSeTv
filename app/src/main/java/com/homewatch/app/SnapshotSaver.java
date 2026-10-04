package com.homewatch.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Environment;
import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A second sink on the same camera track as MotionDetector. When
 * requestCapture() is called, the very next frame that arrives is saved as a
 * JPEG to Pictures/HomeWatch - a timestamped snapshot for evidence.
 */
public class SnapshotSaver implements VideoSink {

    private static final String TAG = "SnapshotSaver";
    private volatile boolean captureRequested = false;
    private final Context context;

    public SnapshotSaver(Context context) {
        this.context = context.getApplicationContext();
    }

    public void requestCapture() {
        captureRequested = true;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (!captureRequested) return;
        captureRequested = false;

        try {
            VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
            byte[] nv21 = i420ToNv21(i420);
            int width = i420.getWidth();
            int height = i420.getHeight();

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 85, jpegOut);

            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HomeWatch");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            String filename = "motion_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".jpg";
            File outFile = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(jpegOut.toByteArray());
            }
            Log.i(TAG, "Saved snapshot: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Snapshot capture failed", e);
        }
    }

    /** Converts I420 (separate Y, U, V planes) to NV21 (Y plane + interleaved VU) for YuvImage/JPEG encoding. */
    private byte[] i420ToNv21(VideoFrame.I420Buffer i420) {
        int width = i420.getWidth();
        int height = i420.getHeight();
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        byte[] out = new byte[width * height + 2 * chromaWidth * chromaHeight];

        ByteBuffer yBuf = i420.getDataY().duplicate();
        int yStride = i420.getStrideY();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                out[pos++] = yBuf.get(row * yStride + col);
            }
        }

        ByteBuffer uBuf = i420.getDataU().duplicate();
        ByteBuffer vBuf = i420.getDataV().duplicate();
        int uStride = i420.getStrideU();
        int vStride = i420.getStrideV();
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                out[pos++] = vBuf.get(row * vStride + col);
                out[pos++] = uBuf.get(row * uStride + col);
            }
        }
        return out;
    }
}
