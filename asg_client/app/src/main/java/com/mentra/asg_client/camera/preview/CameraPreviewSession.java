package com.mentra.asg_client.camera.preview;

import com.mentra.asg_client.AsgConstants;

/**
 * Forwards frames from a {@link CameraPreviewFrameSource} (which paces its own captures to the
 * frame interval) into a {@link PreviewFrameSink}, dropping a frame while a send is in flight,
 * and auto-stops on 5s of consecutive POST
 * failures, a 10 minute cap, a camera error, or an explicit stop.
 *
 * <p>Frame callbacks arrive on a camera background thread and sink callbacks on an OkHttp
 * thread; all state transitions are synchronized on {@link #lock}, and listener/source/sink
 * calls are always made outside the lock so a re-entrant call from those cannot deadlock.
 */
public class CameraPreviewSession implements PreviewSession {

    public interface Listener {
        void onStarted();

        void onStopped(String reason);

        void onStats(int sent, int dropped, int failed);
    }

    private final CameraPreviewFrameSource source;
    private final PreviewFrameSink sink;
    private final Clock clock;
    private final Listener listener;
    private final Object lock = new Object();

    private final CameraPreviewFrameSource.FrameListener frameListener =
            new CameraPreviewFrameSource.FrameListener() {
                @Override
                public void onFrame(byte[] jpegBytes, long captureTimeMs) {
                    handleFrame(jpegBytes, captureTimeMs);
                }

                @Override
                public void onError(String reason) {
                    doStop("camera_error");
                }
            };

    private static final long NOT_TRACKING = -1L;

    // Guarded by lock.
    private boolean active;
    private long startedAtMs;
    /** Start time of the current run of consecutive failures; {@link #NOT_TRACKING} when none. */
    private long failureStreakStartMs;
    /** When the currently outstanding sink send was accepted; {@link #NOT_TRACKING} when idle. */
    private long inFlightSinceMs;
    /** Identity of the currently outstanding attempt; {@link #NOT_TRACKING} when idle. */
    private long inFlightAttempt;
    private long attemptSeq;
    private long lastStatsEmitMs;
    private long frameCounter;
    private int sent;
    private int dropped;
    private int failed;

    public CameraPreviewSession(CameraPreviewFrameSource source, PreviewFrameSink sink, Clock clock, Listener listener) {
        this.source = source;
        this.sink = sink;
        this.clock = clock;
        this.listener = listener;
    }

    public void start(PreviewConfig config) {
        synchronized (lock) {
            if (active) {
                return;
            }
            active = true;
            long now = clock.nowMs();
            startedAtMs = now;
            failureStreakStartMs = NOT_TRACKING;
            inFlightSinceMs = NOT_TRACKING;
            inFlightAttempt = NOT_TRACKING;
            attemptSeq = 0;
            lastStatsEmitMs = now;
            sent = 0;
            dropped = 0;
            failed = 0;
            frameCounter = 0;
        }

        sink.configure(config.url, config.token);
        try {
            source.start(config.width, config.height, config.quality, config.intervalMs, frameListener);
        } catch (Exception e) {
            doStop("camera_error");
            return;
        }
        listener.onStarted();
    }

    public void stop(String reason) {
        doStop(reason);
    }

    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    /** Pumps the periodic max-duration and stats checks; safe to call from a timer. */
    public void tick() {
        String stopReason = null;
        int[] statsSnapshot = null;

        synchronized (lock) {
            if (!active) {
                return;
            }
            long now = clock.nowMs();
            if (now - startedAtMs >= AsgConstants.CAMERA_PREVIEW_MAX_DURATION_MS) {
                stopReason = "max_duration";
            } else if (failureStreakStartMs != NOT_TRACKING
                    && now - failureStreakStartMs >= AsgConstants.CAMERA_PREVIEW_FAILURE_WINDOW_MS) {
                stopReason = "post_failures";
            } else if (inFlightSinceMs != NOT_TRACKING
                    && now - inFlightSinceMs >= AsgConstants.CAMERA_PREVIEW_FAILURE_WINDOW_MS) {
                stopReason = "post_failures";
            } else if (now - lastStatsEmitMs >= AsgConstants.CAMERA_PREVIEW_STATS_INTERVAL_MS) {
                statsSnapshot = new int[]{sent, dropped, failed};
                lastStatsEmitMs = now;
            }
        }

        if (stopReason != null) {
            doStop(stopReason);
            return;
        }
        if (statsSnapshot != null) {
            listener.onStats(statsSnapshot[0], statsSnapshot[1], statsSnapshot[2]);
        }
    }

    private void handleFrame(byte[] jpegBytes, long captureTimeMs) {
        String stopReason = null;
        PreviewFrame frameToSend = null;
        int[] statsSnapshot = null;
        long attempt = NOT_TRACKING;

        synchronized (lock) {
            if (!active) {
                return;
            }
            long now = clock.nowMs();
            if (now - startedAtMs >= AsgConstants.CAMERA_PREVIEW_MAX_DURATION_MS) {
                stopReason = "max_duration";
            } else {
                // The source already paces captures to intervalMs, so every frame is due; the
                // only choice left is send (sink idle) or drop (a send is still in flight).
                frameCounter++;
                if (inFlightSinceMs == NOT_TRACKING) {
                    // Mark in-flight BEFORE calling the sink: a real sink's callback can run
                    // on another thread and fire before trySend() returns to us (e.g. an
                    // immediate connection failure), so marking after the call would race a
                    // synchronous/very-fast callback and leave the marker stuck forever.
                    attemptSeq++;
                    attempt = attemptSeq;
                    inFlightSinceMs = now;
                    inFlightAttempt = attempt;
                    frameToSend = new PreviewFrame(jpegBytes, frameCounter, captureTimeMs);
                } else {
                    dropped++;
                    // Only judge staleness when we are NOT about to make a fresh attempt: a fresh
                    // attempt's own callback (onSinkResult) decides its fate, so a large
                    // intervalMs landing exactly on the failure window must not preempt it
                    // (e.g. a single failure followed by a success 5s later must not stop).
                    if (now - inFlightSinceMs >= AsgConstants.CAMERA_PREVIEW_FAILURE_WINDOW_MS) {
                        // A hung sink never calls back, so this bounds a dead phone to ~5s.
                        stopReason = "post_failures";
                    }
                }
                if (stopReason == null && now - lastStatsEmitMs >= AsgConstants.CAMERA_PREVIEW_STATS_INTERVAL_MS) {
                    statsSnapshot = new int[]{sent, dropped, failed};
                    lastStatsEmitMs = now;
                }
            }
        }

        if (stopReason != null) {
            doStop(stopReason);
            return;
        }

        if (frameToSend != null) {
            long attemptId = attempt;
            boolean accepted = sink.trySend(frameToSend, success -> onSinkResult(attemptId, success));
            if (!accepted) {
                synchronized (lock) {
                    dropped++;
                    // Only clear if this attempt is still the one tracked: a synchronous callback
                    // may already have cleared (and even started a new attempt) before trySend
                    // returned false here in some hypothetical sink implementation.
                    if (inFlightAttempt == attemptId) {
                        inFlightSinceMs = NOT_TRACKING;
                        inFlightAttempt = NOT_TRACKING;
                    }
                }
            }
        }

        if (statsSnapshot != null) {
            listener.onStats(statsSnapshot[0], statsSnapshot[1], statsSnapshot[2]);
        }
    }

    private void onSinkResult(long attempt, boolean success) {
        String stopReason = null;
        synchronized (lock) {
            if (!active) {
                // Late callback after stop; ignore.
                return;
            }
            long now = clock.nowMs();
            if (inFlightAttempt == attempt) {
                inFlightSinceMs = NOT_TRACKING;
                inFlightAttempt = NOT_TRACKING;
            }
            if (success) {
                sent++;
                failureStreakStartMs = NOT_TRACKING;
            } else {
                failed++;
                if (failureStreakStartMs == NOT_TRACKING) {
                    failureStreakStartMs = now;
                } else if (now - failureStreakStartMs >= AsgConstants.CAMERA_PREVIEW_FAILURE_WINDOW_MS) {
                    stopReason = "post_failures";
                }
            }
        }
        if (stopReason != null) {
            doStop(stopReason);
        }
    }

    private void doStop(String reason) {
        boolean shouldNotify;
        synchronized (lock) {
            shouldNotify = active;
            active = false;
        }
        if (!shouldNotify) {
            return;
        }
        source.stop();
        sink.close();
        listener.onStopped(reason);
    }
}
