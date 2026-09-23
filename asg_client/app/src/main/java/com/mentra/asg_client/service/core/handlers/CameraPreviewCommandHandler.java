package com.mentra.asg_client.service.core.handlers;

import android.content.Context;
import android.util.Log;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.camera.CameraNeoService;
import com.mentra.asg_client.camera.preview.Camera2PreviewFrameSource;
import com.mentra.asg_client.camera.preview.CameraPreviewSession;
import com.mentra.asg_client.camera.preview.Clock;
import com.mentra.asg_client.camera.preview.OkHttpPreviewFrameSink;
import com.mentra.asg_client.camera.preview.PreviewConfig;
import com.mentra.asg_client.io.streaming.services.RtmpStreamingService;
import com.mentra.asg_client.io.streaming.services.SrtStreamingService;
import com.mentra.asg_client.io.streaming.services.WhipStreamingService;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;
import com.mentra.asg_client.service.legacy.interfaces.ICommandHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles start_camera_preview / stop_camera_preview: a JPEG frame push from the glasses camera
 * to a phone HTTP receiver (ADR 0012). One preview is active process-wide; any other camera user
 * (stream, video, photo, warm-up) preempts it via {@link #stopIfActive(String)}.
 */
public class CameraPreviewCommandHandler implements ICommandHandler {
    private static final String TAG = "CameraPreviewHandler";

    static final String CMD_START = "start_camera_preview";
    static final String CMD_STOP = "stop_camera_preview";
    private static final String STATUS_TYPE = "camera_preview_status";

    /** Builds a session wired to the given listener. Seam for tests. */
    interface SessionFactory {
        CameraPreviewSession create(CameraPreviewSession.Listener listener);
    }

    /** Camera ownership checks. Seam for tests. */
    interface CameraGate {
        boolean isBusy();

        /** Close a photo camera that is only kept alive (idle), so the preview can open it. */
        void releaseIdleCamera();
    }

    /** Schedules a periodic task; returns a Runnable that cancels it. Seam for tests. */
    interface TickScheduler {
        Runnable schedule(Runnable task, long periodMs);
    }

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    private static CameraPreviewSession activeSession;
    private static Runnable cancelTick;

    private final ICommunicationManager communicationManager;
    private final SessionFactory sessionFactory;
    private final CameraGate cameraGate;
    private final TickScheduler tickScheduler;

    public CameraPreviewCommandHandler(Context context, ICommunicationManager communicationManager) {
        this(
                communicationManager,
                listener ->
                        new CameraPreviewSession(
                                new Camera2PreviewFrameSource(context),
                                new OkHttpPreviewFrameSink(),
                                new Clock.SystemMillisClock(),
                                listener),
                new DefaultCameraGate(),
                new ExecutorTickScheduler());
    }

    CameraPreviewCommandHandler(
            ICommunicationManager communicationManager,
            SessionFactory sessionFactory,
            CameraGate cameraGate,
            TickScheduler tickScheduler) {
        this.communicationManager = communicationManager;
        this.sessionFactory = sessionFactory;
        this.cameraGate = cameraGate;
        this.tickScheduler = tickScheduler;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(CMD_START, CMD_STOP);
    }

    @Override
    public boolean handleCommand(String commandType, JSONObject data) {
        try {
            switch (commandType) {
                case CMD_START:
                    return handleStart(data);
                case CMD_STOP:
                    stopIfActive("requested");
                    return true;
                default:
                    Log.e(TAG, "Unsupported camera preview command: " + commandType);
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling camera preview command: " + commandType, e);
            return false;
        }
    }

    private boolean handleStart(JSONObject data) {
        PreviewConfig config = parseConfig(data);
        if (config == null) {
            Log.w(TAG, "start_camera_preview rejected - invalid params");
            sendStatus("stopped", "reason", "invalid_params");
            return false;
        }
        if (cameraGate.isBusy()) {
            Log.w(TAG, "start_camera_preview rejected - camera busy");
            sendStatus("camera_busy", null, null);
            return false;
        }

        // The phone's interval picker restarts by sending another start.
        stopIfActive("restarted");
        cameraGate.releaseIdleCamera();

        SessionListener listener = new SessionListener(config);
        CameraPreviewSession session = sessionFactory.create(listener);
        listener.session = session;
        synchronized (LOCK) {
            activeSession = session;
            cancelTick =
                    tickScheduler.schedule(
                            session::tick, AsgConstants.CAMERA_PREVIEW_TICK_INTERVAL_MS);
        }
        Log.i(TAG, "Starting camera preview intervalMs=" + config.intervalMs + " " + config.width
                + "x" + config.height + " q=" + config.quality);
        session.start(config);
        return session.isActive();
    }

    /**
     * Stops the active preview, if any, and returns after the frame source's stop() has run.
     * Thread-safe; a no-op when idle.
     */
    public static void stopIfActive(String reason) {
        CameraPreviewSession session;
        synchronized (LOCK) {
            session = activeSession;
        }
        if (session == null) {
            return;
        }
        Log.i(TAG, "Stopping camera preview: " + reason);
        // onStopped() clears the static state; stop() is a no-op if already stopped.
        session.stop(reason);
        clearIfCurrent(session);
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return activeSession != null && activeSession.isActive();
        }
    }

    /** Clears static state. Tests only. */
    static void resetForTest() {
        synchronized (LOCK) {
            activeSession = null;
            cancelTick = null;
        }
    }

    private static void clearIfCurrent(CameraPreviewSession session) {
        Runnable cancel = null;
        synchronized (LOCK) {
            if (activeSession == session) {
                activeSession = null;
                cancel = cancelTick;
                cancelTick = null;
            }
        }
        if (cancel != null) {
            cancel.run();
        }
    }

    /**
     * Parses and clamps start params. Returns null when url (http/https) or token is missing.
     */
    static PreviewConfig parseConfig(JSONObject data) {
        if (data == null) {
            return null;
        }
        String url = data.optString("url", "").trim();
        String token = data.optString("token", "").trim();
        String lowerUrl = url.toLowerCase(java.util.Locale.US);
        boolean httpUrl =
                (lowerUrl.startsWith("http://") && url.length() > "http://".length())
                        || (lowerUrl.startsWith("https://") && url.length() > "https://".length());
        if (!httpUrl || token.isEmpty()) {
            return null;
        }
        long intervalMs =
                clamp(
                        data.optLong("intervalMs", AsgConstants.CAMERA_PREVIEW_DEFAULT_INTERVAL_MS),
                        AsgConstants.CAMERA_PREVIEW_MIN_INTERVAL_MS,
                        AsgConstants.CAMERA_PREVIEW_MAX_INTERVAL_MS);
        int quality =
                (int)
                        clamp(
                                data.optInt("quality", AsgConstants.CAMERA_PREVIEW_DEFAULT_QUALITY),
                                AsgConstants.CAMERA_PREVIEW_MIN_QUALITY,
                                AsgConstants.CAMERA_PREVIEW_MAX_QUALITY);
        int width = data.optInt("width", AsgConstants.CAMERA_PREVIEW_DEFAULT_WIDTH);
        int height = data.optInt("height", AsgConstants.CAMERA_PREVIEW_DEFAULT_HEIGHT);
        if (width <= 0 || height <= 0) {
            width = AsgConstants.CAMERA_PREVIEW_DEFAULT_WIDTH;
            height = AsgConstants.CAMERA_PREVIEW_DEFAULT_HEIGHT;
        }
        return new PreviewConfig(width, height, quality, intervalMs, url, token);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private void sendStatus(String status, String key, Object value) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", STATUS_TYPE);
            msg.put("status", status);
            if (key != null) {
                msg.put(key, value);
            }
            msg.put("timestamp", System.currentTimeMillis());
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Error building camera_preview_status", e);
        }
    }

    private void send(JSONObject msg) {
        Log.d(TAG, "📤 " + msg);
        communicationManager.sendBluetoothResponse(msg);
    }

    private final class SessionListener implements CameraPreviewSession.Listener {
        private final PreviewConfig config;
        volatile CameraPreviewSession session;

        SessionListener(PreviewConfig config) {
            this.config = config;
        }

        @Override
        public void onStarted() {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", STATUS_TYPE);
                msg.put("status", "started");
                msg.put("intervalMs", config.intervalMs);
                msg.put("width", config.width);
                msg.put("height", config.height);
                msg.put("quality", config.quality);
                msg.put("timestamp", System.currentTimeMillis());
                send(msg);
            } catch (JSONException e) {
                Log.e(TAG, "Error building camera_preview_status started", e);
            }
        }

        @Override
        public void onStopped(String reason) {
            Log.i(TAG, "Camera preview stopped: " + reason);
            clearIfCurrent(session);
            sendStatus("stopped", "reason", reason);
        }

        @Override
        public void onStats(int sent, int dropped, int failed) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", STATUS_TYPE);
                msg.put("status", "stats");
                msg.put("sent", sent);
                msg.put("dropped", dropped);
                msg.put("failed", failed);
                msg.put("timestamp", System.currentTimeMillis());
                send(msg);
            } catch (JSONException e) {
                Log.e(TAG, "Error building camera_preview_status stats", e);
            }
        }
    }

    private static final class DefaultCameraGate implements CameraGate {
        @Override
        public boolean isBusy() {
            return CameraNeoService.isCameraBusyOrPending()
                    || RtmpStreamingService.isStreaming()
                    || SrtStreamingService.isStreaming()
                    || WhipStreamingService.isStreaming();
        }

        @Override
        public void releaseIdleCamera() {
            CameraNeoService.closeKeptAliveCamera();
        }
    }

    private static final class ExecutorTickScheduler implements TickScheduler {
        private static final ScheduledExecutorService EXECUTOR =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "CameraPreviewTick");
                            t.setDaemon(true);
                            return t;
                        });

        @Override
        public Runnable schedule(Runnable task, long periodMs) {
            ScheduledFuture<?> future =
                    EXECUTOR.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    task.run();
                                } catch (Exception e) {
                                    Log.e(TAG, "Camera preview tick failed", e);
                                }
                            },
                            periodMs,
                            periodMs,
                            TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    }
}
