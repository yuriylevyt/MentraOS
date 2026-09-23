package com.mentra.asg_client.camera.preview;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.mentra.asg_client.camera.lifecycle.CameraOpener;
import com.mentra.asg_client.utils.WakeLockManager;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * H.264 camera preview source (ADR 0013). One repeating Camera2 request draws straight into the
 * hardware encoder's input Surface: no ImageReader, no JPEG. Encoder output becomes access units
 * through {@link AccessUnitBuilder}, so the first unit is a config unit and every keyframe carries
 * SPS/PPS before it.
 *
 * <p>Threading: every Camera2 and MediaCodec object is created, used and released on this source's
 * HandlerThread. {@link #stop()} posts teardown there and waits (bounded) until the camera has
 * closed and the encoder is released. Called on the handler thread itself, it tears down inline.
 * Teardown order is camera first, encoder second, so the camera never draws into a dead Surface.
 */
public class Camera2H264Source implements AccessUnitSource {
    private static final String TAG = "CameraPreviewH264";
    private static final long WAKE_CPU_MS = 10_000L;
    private static final long WAKE_SCREEN_MS = 5_000L;

    private final Context context;
    private final CameraManager cameraManager;

    private final Object lifecycleLock = new Object();
    // Guarded by lifecycleLock.
    private boolean started;
    private HandlerThread thread;
    private Handler handler;
    private CountDownLatch closedLatch;

    private volatile boolean stopRequested;

    // ---- Handler thread only. ----
    private UnitListener listener;
    private String cameraId;
    private Range<Integer> fpsRange;
    private int afMode = -1;
    private MediaCodecAvcEncoder encoder;
    private Surface encoderSurface;
    private CameraDevice device;
    private CameraCaptureSession session;
    private int openAttempts;
    private boolean opening;
    private boolean closing;
    private boolean errorReported;
    private boolean finished;

    public Camera2H264Source(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    public void start(PreviewConfig config, UnitListener listener) throws Exception {
        synchronized (lifecycleLock) {
            if (started) {
                throw new IllegalStateException("Camera2H264Source already started");
            }
            started = true;
            stopRequested = false;
        }
        try {
            String id = CameraOpener.selectPrimaryCameraId(cameraManager);
            if (id == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "no camera available");
            }
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            int targetFps = (int) Math.max(1, Math.ceil(1000.0 / Math.max(1, config.intervalMs)));
            Range<Integer> range = Camera2PreviewFrameSource.chooseFpsRange(
                    ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), targetFps);
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int af = contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    : -1;
            int sensorFps = range != null ? range.getUpper() : targetFps;
            MediaFormat format = MediaCodecAvcEncoder.buildFormat(config.width, config.height,
                    config.bitrateKbps, sensorFps, config.keyframeIntervalMs, targetFps);

            HandlerThread t = new HandlerThread("CameraPreviewH264");
            t.start();
            Handler h = new Handler(t.getLooper());
            synchronized (lifecycleLock) {
                if (!started) {
                    t.quitSafely(); // stop() raced this start before anything was opened.
                    return;
                }
                thread = t;
                handler = h;
                closedLatch = new CountDownLatch(1);
            }
            // Same as the JPEG source: a sleeping device rejects openCamera "disabled by policy".
            WakeLockManager.acquireFullWakeLockAndBringToForeground(
                    context, WakeLockManager.WakeOwner.CAMERA, WAKE_CPU_MS, WAKE_SCREEN_MS);

            h.post(() -> {
                this.listener = listener;
                this.cameraId = id;
                this.fpsRange = range;
                this.afMode = af;
                openAttempts = 0;
                opening = false;
                closing = false;
                errorReported = false;
                finished = false;
                AccessUnitBuilder builder = new AccessUnitBuilder(unit -> {
                    if (!stopRequested) {
                        listener.onUnit(unit);
                    }
                });
                encoder = new MediaCodecAvcEncoder();
                try {
                    encoderSurface = encoder.start(format, h, new MediaCodecAvcEncoder.Output() {
                        @Override
                        public void onEncoded(byte[] data, long ptsUs, boolean isConfig, boolean isKeyframe) {
                            builder.accept(data, ptsUs, isConfig, isKeyframe);
                        }

                        @Override
                        public void onError(String reason) {
                            fail(reason);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "encoder start failed", e);
                    fail("encoder_error");
                    return;
                }
                Log.i(TAG, "start camera " + id + " " + config.width + "x" + config.height
                        + " bitrateKbps=" + config.bitrateKbps + " keyframeIntervalMs=" + config.keyframeIntervalMs
                        + " fpsRange=" + range + " targetFps=" + targetFps + " codec=" + encoder.codecName());
                openCamera();
            });
        } catch (Exception e) {
            HandlerThread t;
            synchronized (lifecycleLock) {
                started = false;
                t = thread;
                thread = null;
                handler = null;
            }
            if (t != null) {
                t.quitSafely();
            }
            throw e;
        }
    }

    @Override
    public void requestKeyframe() {
        Handler h;
        synchronized (lifecycleLock) {
            h = handler;
        }
        if (h != null) {
            h.post(() -> {
                if (encoder != null && !stopRequested) {
                    encoder.requestKeyframe();
                }
            });
        }
    }

    @Override
    public void stop() {
        Handler h;
        HandlerThread t;
        CountDownLatch latch;
        synchronized (lifecycleLock) {
            if (!started) {
                return;
            }
            started = false;
            stopRequested = true;
            h = handler;
            t = thread;
            latch = closedLatch;
        }
        if (h == null || t == null) {
            return;
        }
        if (Looper.myLooper() == t.getLooper()) {
            teardown();
            return;
        }
        if (!h.post(this::teardown)) {
            return; // Looper already quit: teardown finished earlier.
        }
        try {
            if (!latch.await(Camera2PreviewFrameSource.STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "stop() timed out waiting for the camera to close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ handler thread

    private void openCamera() {
        if (stopRequested) {
            teardown();
            return;
        }
        openAttempts++;
        opening = true;
        try {
            cameraManager.openCamera(cameraId, stateCallback, handler);
        } catch (CameraAccessException e) {
            opening = false;
            if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                onAccessRestricted();
            } else {
                Log.e(TAG, "openCamera failed", e);
                fail("open_failed");
            }
        } catch (RuntimeException e) {
            opening = false;
            Log.e(TAG, "openCamera failed", e);
            fail("open_failed");
        }
    }

    private void onAccessRestricted() {
        if (!Camera2PreviewFrameSource.shouldRetryRestrictedOpen(
                openAttempts, Camera2PreviewFrameSource.MAX_OPEN_ATTEMPTS)) {
            fail("camera_access_restricted");
            return;
        }
        Log.w(TAG, "camera access restricted, attempt " + openAttempts + ", retrying");
        WakeLockManager.acquireFullWakeLockAndBringToForeground(
                context, WakeLockManager.WakeOwner.CAMERA, WAKE_CPU_MS, WAKE_SCREEN_MS);
        handler.postDelayed(this::openCamera, Camera2PreviewFrameSource.OPEN_RETRY_DELAY_MS);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice d) {
            opening = false;
            device = d;
            if (stopRequested) {
                teardown();
                return;
            }
            try {
                createSession();
            } catch (Exception e) {
                Log.e(TAG, "failed to create capture session", e);
                fail("configure_failed");
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice d) {
            opening = false;
            if (device == null) {
                device = d;
            }
            fail("camera_disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice d, int error) {
            opening = false;
            if (device == null) {
                device = d;
            }
            if (error == ERROR_CAMERA_DISABLED && !stopRequested) {
                closeDevice();
                onAccessRestricted();
                return;
            }
            fail("camera_error_" + error);
        }

        @Override
        public void onClosed(@NonNull CameraDevice d) {
            closing = false;
            if (stopRequested) {
                finish();
            }
        }
    };

    private void createSession() throws CameraAccessException {
        device.createCaptureSession(Collections.singletonList(encoderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession s) {
                        if (stopRequested) {
                            return; // Closing the device tears the session down.
                        }
                        session = s;
                        try {
                            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            b.addTarget(encoderSurface);
                            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                            if (fpsRange != null) {
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                            }
                            if (afMode >= 0) {
                                b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
                            }
                            s.setRepeatingRequest(b.build(), null, handler);
                            Log.i(TAG, "repeating request into the encoder started");
                        } catch (CameraAccessException | RuntimeException e) {
                            Log.e(TAG, "failed to start the repeating request", e);
                            fail("capture_request_failed");
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                        fail("configure_failed");
                    }
                }, handler);
    }

    private void fail(String reason) {
        if (!stopRequested && !errorReported) {
            errorReported = true;
            Log.e(TAG, "error: " + reason);
            if (listener != null) {
                listener.onError(reason);
            }
        }
        teardown();
    }

    /** Idempotent. Stops the camera first; {@link #finish()} releases the encoder once it closed. */
    private void teardown() {
        stopRequested = true;
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (Exception ignored) {
                // Session may already be invalid.
            }
            session = null;
        }
        closeDevice();
        finish();
    }

    private void closeDevice() {
        if (device == null) {
            return;
        }
        CameraDevice d = device;
        device = null;
        closing = true;
        try {
            d.close();
        } catch (Exception e) {
            closing = false;
            Log.w(TAG, "device.close() threw", e);
        }
    }

    /** Runs once the camera is closed (or never opened): releases the encoder and quits the thread. */
    private void finish() {
        if (finished || opening || closing) {
            return;
        }
        finished = true;
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        Log.i(TAG, "stopped");
        CountDownLatch latch;
        HandlerThread t;
        synchronized (lifecycleLock) {
            latch = closedLatch;
            t = thread;
        }
        if (latch != null) {
            latch.countDown();
        }
        if (t != null) {
            t.quitSafely();
        }
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) {
            return false;
        }
        for (int v : values) {
            if (v == target) {
                return true;
            }
        }
        return false;
    }
}
