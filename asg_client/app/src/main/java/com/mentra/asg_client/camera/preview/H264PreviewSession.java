package com.mentra.asg_client.camera.preview;

import com.mentra.asg_client.AsgConstants;

/**
 * Runs an H.264 camera preview (ADR 0013): access units from an {@link AccessUnitSource} go to a
 * {@link StreamTransport}. Same stop rules as the JPEG {@link CameraPreviewSession}: 5 s of
 * consecutive transport failures, 5 s with a connection open but nothing delivered, the 10 minute
 * cap, a camera/encoder error, or an explicit stop. Plus a 401, which stops at once
 * ({@code unauthorized}).
 *
 * <p>A failed transport is replaced on the next {@link #tick()} (about once a second), with a
 * keyframe request so the new connection starts decodable. Callbacks from a replaced transport are
 * ignored. All state is guarded by {@link #lock}; source/transport/listener calls happen outside it.
 */
public class H264PreviewSession implements PreviewSession {

    /** Opens a transport for the given config. Seam for tests. */
    public interface TransportFactory {
        StreamTransport open(PreviewConfig config, StreamTransport.Events events);
    }

    private static final long NOT_TRACKING = -1L;

    private final AccessUnitSource source;
    private final TransportFactory transports;
    private final Clock clock;
    private final CameraPreviewSession.Listener listener;
    private final Object lock = new Object();

    // Guarded by lock.
    private boolean active;
    private PreviewConfig config;
    private StreamTransport transport;
    private long transportGeneration;
    private long startedAtMs;
    private long failureStreakStartMs;
    private long lastProgressMs;
    private long lastStatsEmitMs;
    private int sent;
    private int dropped;
    private int failed;

    private final AccessUnitSource.UnitListener unitListener = new AccessUnitSource.UnitListener() {
        @Override
        public void onUnit(AccessUnit unit) {
            StreamTransport target;
            synchronized (lock) {
                if (!active) {
                    return;
                }
                target = transport;
                if (target == null) {
                    dropped++;
                }
            }
            if (target != null) {
                target.offer(unit);
            }
        }

        @Override
        public void onError(String reason) {
            doStop("camera_error");
        }
    };

    public H264PreviewSession(AccessUnitSource source, TransportFactory transports, Clock clock,
            CameraPreviewSession.Listener listener) {
        this.source = source;
        this.transports = transports;
        this.clock = clock;
        this.listener = listener;
    }

    @Override
    public void start(PreviewConfig config) {
        StreamTransport first;
        synchronized (lock) {
            if (active) {
                return;
            }
            active = true;
            this.config = config;
            long now = clock.nowMs();
            startedAtMs = now;
            lastStatsEmitMs = now;
            failureStreakStartMs = NOT_TRACKING;
            sent = 0;
            dropped = 0;
            failed = 0;
            first = openTransportLocked(now);
        }
        first.start();
        try {
            source.start(config, unitListener);
        } catch (Exception e) {
            doStop("camera_error");
            return;
        }
        listener.onStarted();
    }

    @Override
    public void stop(String reason) {
        doStop(reason);
    }

    @Override
    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    @Override
    public void tick() {
        String stopReason = null;
        int[] stats = null;
        StreamTransport reopened = null;
        synchronized (lock) {
            if (!active) {
                return;
            }
            long now = clock.nowMs();
            long window = AsgConstants.CAMERA_PREVIEW_FAILURE_WINDOW_MS;
            if (now - startedAtMs >= AsgConstants.CAMERA_PREVIEW_MAX_DURATION_MS) {
                stopReason = "max_duration";
            } else if (failureStreakStartMs != NOT_TRACKING && now - failureStreakStartMs >= window) {
                stopReason = "post_failures";
            } else if (transport != null && now - lastProgressMs >= window) {
                // Connected (or connecting) but nothing delivered: a stuck write never fails.
                stopReason = "post_failures";
            } else {
                if (transport == null) {
                    reopened = openTransportLocked(now);
                }
                if (now - lastStatsEmitMs >= AsgConstants.CAMERA_PREVIEW_STATS_INTERVAL_MS) {
                    stats = new int[] {sent, dropped, failed};
                    lastStatsEmitMs = now;
                }
            }
        }
        if (stopReason != null) {
            doStop(stopReason);
            return;
        }
        if (reopened != null) {
            reopened.start();
            source.requestKeyframe();
        }
        if (stats != null) {
            listener.onStats(stats[0], stats[1], stats[2]);
        }
    }

    private StreamTransport openTransportLocked(long now) {
        transportGeneration++;
        lastProgressMs = now;
        transport = transports.open(config, eventsFor(transportGeneration));
        return transport;
    }

    private StreamTransport.Events eventsFor(long generation) {
        return new StreamTransport.Events() {
            @Override
            public void onConnected() {
                onProgress(generation, false);
            }

            @Override
            public void onDelivered(AccessUnit unit) {
                onProgress(generation, true);
            }

            @Override
            public void onDropped(int units) {
                synchronized (lock) {
                    if (active && generation == transportGeneration) {
                        dropped += units;
                    }
                }
            }

            @Override
            public void onKeyframeNeeded() {
                if (isCurrent(generation)) {
                    source.requestKeyframe();
                }
            }

            @Override
            public void onUnauthorized() {
                if (isCurrent(generation)) {
                    doStop("unauthorized");
                }
            }

            @Override
            public void onFailure(String reason) {
                onTransportFailure(generation);
            }
        };
    }

    private boolean isCurrent(long generation) {
        synchronized (lock) {
            return active && generation == transportGeneration && transport != null;
        }
    }

    private void onProgress(long generation, boolean delivered) {
        synchronized (lock) {
            if (!active || generation != transportGeneration) {
                return;
            }
            if (delivered) {
                sent++;
            }
            failureStreakStartMs = NOT_TRACKING;
            lastProgressMs = clock.nowMs();
        }
    }

    private void onTransportFailure(long generation) {
        StreamTransport dead;
        boolean stop = false;
        synchronized (lock) {
            if (!active || generation != transportGeneration || transport == null) {
                return;
            }
            long now = clock.nowMs();
            failed++;
            dead = transport;
            transport = null;
            if (failureStreakStartMs == NOT_TRACKING) {
                failureStreakStartMs = now;
            } else if (now - failureStreakStartMs >= AsgConstants.CAMERA_PREVIEW_FAILURE_WINDOW_MS) {
                stop = true;
            }
        }
        dead.close();
        if (stop) {
            doStop("post_failures");
        }
    }

    private void doStop(String reason) {
        StreamTransport current;
        synchronized (lock) {
            if (!active) {
                return;
            }
            active = false;
            current = transport;
            transport = null;
        }
        source.stop();
        if (current != null) {
            current.close();
        }
        listener.onStopped(reason);
    }
}
