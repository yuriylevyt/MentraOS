package com.mentra.asg_client.camera.preview;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import com.mentra.asg_client.camera.lifecycle.CameraOpener;
import com.mentra.asg_client.camera.policy.JpegOrientationResolver;
import com.mentra.asg_client.utils.WakeLockManager;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * On-demand JPEG preview capture over Camera2.
 *
 * <p>A repeating request feeds only a tiny YUV {@link ImageReader} whose images are closed
 * immediately: its one job is to keep AE/AWB/AF converged. JPEG frames come from single {@code
 * capture()} requests into a separate JPEG reader, issued by {@link CapturePacer} roughly every
 * {@code intervalMs} with at most {@link #MAX_OUTSTANDING_CAPTURES} in flight. So the chip encodes
 * about one JPEG per interval instead of one per sensor frame.
 *
 * <p>Threading: every Camera2 object is created, used and closed only on this source's
 * HandlerThread. {@link #stop()} posts teardown there and blocks the caller until the device's
 * {@code onClosed} has fired (bounded by {@link #STOP_TIMEOUT_MS}); called on the handler thread
 * itself it runs inline and returns without waiting. The thread quits itself once every device it
 * opened has reported {@code onClosed}.
 */
public class Camera2PreviewFrameSource implements CameraPreviewFrameSource {

    private static final String TAG = "CameraPreviewSource";
    private static final long STATS_LOG_INTERVAL_MS = 5_000L;
    static final long STOP_TIMEOUT_MS = 1_500L;

    /** Attempts before a policy-restricted open is reported as camera_access_restricted. */
    static final int MAX_OPEN_ATTEMPTS = 4;
    static final long OPEN_RETRY_DELAY_MS = 500L;

    /** Wake window used to get past "Camera disabled by policy" on a sleeping device. */
    private static final long WAKE_CPU_MS = 10_000L;
    private static final long WAKE_SCREEN_MS = 5_000L;

    /**
     * Captures allowed in flight at once. On the MT6761 a single capture() takes ~410 ms from
     * request to image at [15,15] (it queues behind ~6 in-flight repeating frames; the image is
     * not older for it), so one-at-a-time caps the rate at ~2.4 fps. Five in flight reaches the
     * 10 fps a 100 ms interval asks for; the pacer still bounds the average rate to the interval.
     */
    static final int MAX_OUTSTANDING_CAPTURES = 5;

    static final int MIN_YUV_WIDTH = 320;
    static final int MIN_YUV_HEIGHT = 240;

    private final Context context;
    private final CameraManager cameraManager;

    // Lifecycle, guarded by lifecycleLock.
    private final Object lifecycleLock = new Object();
    private boolean started;
    private HandlerThread thread;
    private Handler handler;
    private CountDownLatch closedLatch;

    /** Set by stop() on any thread; read by callbacks on the handler thread. */
    private volatile boolean stopRequested;

    // ---- Everything below is touched only on the handler thread. ----
    private FrameListener listener;
    private String cameraId;
    private int width;
    private int height;
    private int quality;
    private long intervalMs;
    private Size yuvSize;
    private Range<Integer> fpsRange;
    private int afMode = -1;
    private int jpegOrientation;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private ImageReader yuvReader;
    private CaptureRequest captureRequest;
    private CapturePacer pacer;

    private boolean opening;
    private int openAttempts;
    private long openRequestedAtMs;
    private int pendingCloses;
    private long closeRequestedAtMs;
    private boolean tearingDown;
    private boolean finished;
    private boolean errorReported;

    private long statsWindowStartMs;
    private int jpegsInWindow;
    private int failedInWindow;
    private final List<Long> latenciesInWindow = new ArrayList<>();

    private final Runnable captureTick = this::onCaptureTick;
    private final Runnable openRunnable = this::openOnHandler;

    public Camera2PreviewFrameSource(Context context) {
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    public void start(int width, int height, int quality, long intervalMs, FrameListener listener) throws Exception {
        synchronized (lifecycleLock) {
            if (started) {
                throw new IllegalStateException("Camera2PreviewFrameSource already started");
            }
            started = true;
            stopRequested = false;
        }
        try {
            // Characteristics queries create no Camera2 objects; do them here so bad params fail
            // synchronously and the caller sees an exception, not a late error.
            String id = CameraOpener.selectPrimaryCameraId(cameraManager);
            if (id == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "no camera available");
            }
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            StreamConfigurationMap map = CameraOpener.streamMapOrNull(ch);
            if (!containsSize(CameraOpener.jpegOutputSizes(map), width, height)) {
                throw new IllegalArgumentException("unsupported_size " + width + "x" + height);
            }
            Size yuv = chooseYuvSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            if (yuv == null) {
                throw new IllegalArgumentException("no YUV_420_888 output size");
            }
            int targetFps = (int) Math.max(1, Math.ceil(1000.0 / Math.max(1, intervalMs)));
            Range<Integer> range =
                    chooseFpsRange(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), targetFps);
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int af = contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    : contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            : -1;
            int orientation = JpegOrientationResolver.getJpegOrientation(context);

            HandlerThread t = new HandlerThread("CameraPreviewSource");
            t.start();
            Handler h = new Handler(t.getLooper());
            synchronized (lifecycleLock) {
                if (!started) {
                    // stop() raced this start before the thread existed; nothing was opened.
                    t.quitSafely();
                    return;
                }
                thread = t;
                handler = h;
                closedLatch = new CountDownLatch(1);
            }

            // Same mechanism as the photo path (CameraNeoService.wakeUpScreen): wake the screen and
            // bring the app to the front, otherwise a sleeping device rejects openCamera with
            // "Camera disabled by policy".
            WakeLockManager.acquireFullWakeLockAndBringToForeground(
                    context, WakeLockManager.WakeOwner.CAMERA, WAKE_CPU_MS, WAKE_SCREEN_MS);

            h.post(() -> {
                resetHandlerState();
                this.listener = listener;
                this.cameraId = id;
                this.width = width;
                this.height = height;
                this.quality = quality;
                this.intervalMs = intervalMs;
                this.yuvSize = yuv;
                this.fpsRange = range;
                this.afMode = af;
                this.jpegOrientation = orientation;
                Log.i(TAG, "start camera " + id + " jpeg=" + width + "x" + height + " q=" + quality
                        + " intervalMs=" + intervalMs + " yuv=" + yuv + " fpsRange=" + range
                        + " orientation=" + orientation);
                openOnHandler();
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

    private void resetHandlerState() {
        device = null;
        session = null;
        jpegReader = null;
        yuvReader = null;
        captureRequest = null;
        pacer = null;
        opening = false;
        openAttempts = 0;
        pendingCloses = 0;
        tearingDown = false;
        finished = false;
        errorReported = false;
        jpegsInWindow = 0;
        failedInWindow = 0;
        latenciesInWindow.clear();
    }

    // ------------------------------------------------------------------ open / session

    private void openOnHandler() {
        if (stopRequested || tearingDown) {
            maybeFinish();
            return;
        }
        openAttempts++;
        opening = true;
        openRequestedAtMs = SystemClock.elapsedRealtime();
        try {
            cameraManager.openCamera(cameraId, stateCallback, handler);
        } catch (CameraAccessException e) {
            opening = false;
            if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                onAccessRestricted("openCamera threw CAMERA_DISABLED");
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

    private void onAccessRestricted(String detail) {
        if (!shouldRetryRestrictedOpen(openAttempts, MAX_OPEN_ATTEMPTS)) {
            Log.e(TAG, "camera access restricted after " + openAttempts + " attempts (" + detail + ")");
            fail("camera_access_restricted");
            return;
        }
        Log.w(TAG, "camera access restricted (" + detail + "), attempt " + openAttempts
                + "/" + MAX_OPEN_ATTEMPTS + ", retrying in " + OPEN_RETRY_DELAY_MS + "ms");
        WakeLockManager.acquireFullWakeLockAndBringToForeground(
                context, WakeLockManager.WakeOwner.CAMERA, WAKE_CPU_MS, WAKE_SCREEN_MS);
        handler.postDelayed(openRunnable, OPEN_RETRY_DELAY_MS);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice d) {
            opening = false;
            Log.i(TAG, "opened in " + (SystemClock.elapsedRealtime() - openRequestedAtMs)
                    + "ms (attempt " + openAttempts + ")");
            device = d;
            if (stopRequested || tearingDown) {
                // stop() ran while we were opening: close deterministically.
                closeDevice();
                maybeFinish();
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
        public void onDisconnected(CameraDevice d) {
            opening = false;
            Log.w(TAG, "camera disconnected");
            if (device == null) {
                device = d;
            }
            fail("camera_disconnected");
        }

        @Override
        public void onError(CameraDevice d, int error) {
            opening = false;
            if (error == ERROR_CAMERA_DISABLED && !stopRequested && !tearingDown) {
                device = d;
                closeDevice();
                onAccessRestricted("onError ERROR_CAMERA_DISABLED");
                return;
            }
            if (device == null) {
                device = d;
            }
            fail("camera_error_" + error);
        }

        @Override
        public void onClosed(CameraDevice d) {
            pendingCloses = Math.max(0, pendingCloses - 1);
            Log.i(TAG, "close->onClosed " + (SystemClock.elapsedRealtime() - closeRequestedAtMs) + "ms");
            maybeFinish();
        }
    };

    private void createSession() throws CameraAccessException {
        yuvReader = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(), ImageFormat.YUV_420_888, 2);
        yuvReader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
            } catch (RuntimeException ignored) {
                // Reader closing.
            } finally {
                if (img != null) {
                    img.close();
                }
            }
        }, handler);
        jpegReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        jpegReader.setOnImageAvailableListener(this::onJpegAvailable, handler);

        List<Surface> surfaces = Arrays.asList(yuvReader.getSurface(), jpegReader.getSurface());
        long t0 = SystemClock.elapsedRealtime();
        device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                if (stopRequested || tearingDown) {
                    return; // device close tears the session down.
                }
                session = s;
                Log.i(TAG, "session configured in " + (SystemClock.elapsedRealtime() - t0) + "ms");
                try {
                    startRepeatingAndCaptures();
                } catch (Exception e) {
                    Log.e(TAG, "failed to start requests", e);
                    fail("capture_request_failed");
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                fail("configure_failed");
            }
        }, handler);
    }

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (fpsRange != null) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        }
        if (afMode >= 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        }
    }

    private void startRepeatingAndCaptures() throws CameraAccessException {
        // TEMPLATE_RECORD for both: steady-pacing bias, and the same template on the repeating and
        // single requests keeps the HAL from re-tuning 3A on every capture.
        CaptureRequest.Builder repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        repeating.addTarget(yuvReader.getSurface());
        applyCommon(repeating);
        session.setRepeatingRequest(repeating.build(), null, handler);

        // JPEG-only target: adding the YUV surface too made no difference on this HAL.
        CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        still.addTarget(jpegReader.getSurface());
        applyCommon(still);
        still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
        still.set(CaptureRequest.JPEG_QUALITY, (byte) quality);
        captureRequest = still.build();

        long now = SystemClock.elapsedRealtime();
        pacer = new CapturePacer(intervalMs, now, MAX_OUTSTANDING_CAPTURES);
        statsWindowStartMs = now;
        Log.i(TAG, "repeating YUV " + yuvSize + " started, fpsRange=" + fpsRange
                + ", JPEG captures every " + intervalMs + "ms, max in flight " + MAX_OUTSTANDING_CAPTURES);
        onCaptureTick();
    }

    // ------------------------------------------------------------------ capture loop

    private void onCaptureTick() {
        if (session == null || pacer == null || tearingDown) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pacer.tryIssue(now)) {
            try {
                session.capture(captureRequest, captureCallback, handler);
            } catch (CameraAccessException | RuntimeException e) {
                Log.e(TAG, "capture() failed", e);
                pacer.onDelivered(now);
                fail("capture_request_failed");
                return;
            }
        }
        handler.removeCallbacks(captureTick);
        handler.postDelayed(captureTick, pacer.delayUntilNextCheckMs(now));
        logStatsIfDue(now);
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
            if (pacer == null) {
                return;
            }
            failedInWindow++;
            Log.w(TAG, "capture failed reason=" + f.getReason());
            pacer.onDelivered(SystemClock.elapsedRealtime());
            onCaptureTick();
        }
    };

    private void onJpegAvailable(ImageReader reader) {
        Image image = null;
        byte[] bytes = null;
        try {
            image = reader.acquireNextImage();
            if (image == null) {
                return;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
        } catch (RuntimeException e) {
            Log.w(TAG, "onJpegAvailable failed", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
        long now = SystemClock.elapsedRealtime();
        long latency = pacer == null ? -1 : pacer.onDelivered(now);
        if (bytes != null) {
            jpegsInWindow++;
            if (latency >= 0) {
                latenciesInWindow.add(latency);
            }
            if (!stopRequested && !tearingDown && listener != null) {
                listener.onFrame(bytes, now);
            }
        }
        onCaptureTick();
    }

    private void logStatsIfDue(long now) {
        long elapsed = now - statsWindowStartMs;
        if (elapsed < STATS_LOG_INTERVAL_MS) {
            return;
        }
        Collections.sort(latenciesInWindow);
        long p50 = latenciesInWindow.isEmpty() ? -1 : latenciesInWindow.get(latenciesInWindow.size() / 2);
        long max = latenciesInWindow.isEmpty() ? -1 : latenciesInWindow.get(latenciesInWindow.size() - 1);
        Log.i(TAG, String.format(java.util.Locale.US,
                "jpeg %d in %dms (%.1f fps) latency p50=%dms max=%dms skippedTicks=%d failed=%d",
                jpegsInWindow, elapsed, jpegsInWindow * 1000.0 / elapsed, p50, max,
                pacer.takeSkipped(), failedInWindow));
        statsWindowStartMs = now;
        jpegsInWindow = 0;
        failedInWindow = 0;
        latenciesInWindow.clear();
    }

    // ------------------------------------------------------------------ errors / teardown

    private void fail(String reason) {
        reportError(reason);
        teardownOnHandler();
    }

    private void reportError(String reason) {
        if (stopRequested || errorReported) {
            // Never call onError after stop(): a caller-initiated stop is not a capture error.
            return;
        }
        errorReported = true;
        Log.e(TAG, "error: " + reason);
        if (listener != null) {
            listener.onError(reason);
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
            // On our own thread (e.g. the listener stopping us from onError): can't wait for
            // onClosed, which is delivered on this very thread.
            teardownOnHandler();
            return;
        }
        long t0 = SystemClock.elapsedRealtime();
        if (!h.post(this::teardownOnHandler)) {
            return; // Looper already quit: teardown finished earlier.
        }
        try {
            if (latch.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.i(TAG, "stop() returned after " + (SystemClock.elapsedRealtime() - t0) + "ms, camera closed");
            } else {
                Log.w(TAG, "stop() timed out after " + STOP_TIMEOUT_MS + "ms waiting for camera close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Idempotent. Handler thread only. */
    private void teardownOnHandler() {
        stopRequested = true;
        if (!tearingDown) {
            tearingDown = true;
            handler.removeCallbacks(captureTick);
            handler.removeCallbacks(openRunnable);
            if (session != null) {
                try {
                    session.stopRepeating();
                } catch (Exception ignored) {
                    // Session may already be invalid.
                }
                session = null;
            }
            closeDevice();
        }
        maybeFinish();
    }

    private void closeDevice() {
        if (device == null) {
            return;
        }
        closeRequestedAtMs = SystemClock.elapsedRealtime();
        pendingCloses++;
        try {
            device.close(); // also closes the capture session
        } catch (Exception e) {
            pendingCloses--;
            Log.w(TAG, "device.close() threw", e);
        }
        device = null;
    }

    /** Quits the thread once teardown was requested and every opened device reported onClosed. */
    private void maybeFinish() {
        if (!tearingDown && !stopRequested) {
            return;
        }
        if (finished || opening || pendingCloses > 0) {
            return;
        }
        tearingDown = true;
        finished = true;
        handler.removeCallbacks(captureTick);
        handler.removeCallbacks(openRunnable);
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (yuvReader != null) {
            yuvReader.close();
            yuvReader = null;
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

    // ------------------------------------------------------------------ pure helpers

    /** A policy-restricted open is retried (with a fresh wake) until {@code maxAttempts}. */
    static boolean shouldRetryRestrictedOpen(int attemptsSoFar, int maxAttempts) {
        return attemptsSoFar < maxAttempts;
    }

    /**
     * Smallest YUV size of at least {@link #MIN_YUV_WIDTH}x{@link #MIN_YUV_HEIGHT} (any aspect: the
     * metering stream is never looked at, and this HAL offers no small 16:9 size), else the
     * largest size available.
     */
    static Size chooseYuvSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        Size largest = sizes[0];
        for (Size s : sizes) {
            if (area(s) > area(largest)) {
                largest = s;
            }
            if (s.getWidth() >= MIN_YUV_WIDTH && s.getHeight() >= MIN_YUV_HEIGHT
                    && (best == null || area(s) < area(best))) {
                best = s;
            }
        }
        return best != null ? best : largest;
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    private static boolean containsSize(Size[] sizes, int width, int height) {
        if (sizes == null) {
            return false;
        }
        for (Size s : sizes) {
            if (s.getWidth() == width && s.getHeight() == height) {
                return true;
            }
        }
        return false;
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

    /**
     * Picks the AE target-fps range for the repeating (metering) request: the lowest sensor rate
     * that still gives a frame slot for every capture at {@code targetFps}. The sensor rate, not the
     * JPEG count, sets the HAL's CPU floor: measured on the MT6761 with no JPEGs, camerahalserver
     * averaged ~40% at [15,15] and ~58% at [5,30] (which runs at 30 fps in normal light).
     *
     * <ol>
     *   <li>Prefer a fixed range {@code [x,x]} with the smallest {@code x >= targetFps}.
     *   <li>If none, pick the range with the smallest upper bound {@code >= targetFps} (ties
     *       broken by the narrowest span).
     *   <li>If {@code targetFps} exceeds every upper bound, use the range with the highest upper
     *       bound (narrowest span on ties).
     * </ol>
     */
    static Range<Integer> chooseFpsRange(Range<Integer>[] available, int targetFps) {
        if (available == null || available.length == 0) {
            return null;
        }

        Range<Integer> bestFixed = null;
        for (Range<Integer> r : available) {
            if (r.getLower().equals(r.getUpper()) && r.getLower() >= targetFps) {
                if (bestFixed == null || r.getLower() < bestFixed.getLower()) {
                    bestFixed = r;
                }
            }
        }
        if (bestFixed != null) {
            return bestFixed;
        }

        Range<Integer> bestSufficient = null;
        for (Range<Integer> r : available) {
            if (r.getUpper() >= targetFps) {
                if (bestSufficient == null
                        || r.getUpper() < bestSufficient.getUpper()
                        || (r.getUpper().equals(bestSufficient.getUpper()) && span(r) < span(bestSufficient))) {
                    bestSufficient = r;
                }
            }
        }
        if (bestSufficient != null) {
            return bestSufficient;
        }

        Range<Integer> bestHighest = available[0];
        for (Range<Integer> r : available) {
            if (r.getUpper() > bestHighest.getUpper()
                    || (r.getUpper().equals(bestHighest.getUpper()) && span(r) < span(bestHighest))) {
                bestHighest = r;
            }
        }
        return bestHighest;
    }

    private static int span(Range<Integer> r) {
        return r.getUpper() - r.getLower();
    }
}
