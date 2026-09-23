package com.mentra.asg_client.camera.preview;

import com.mentra.asg_client.AsgConstants;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The H.264 preview connection (ADR 0013): a plain socket, a hand-written HTTP head, the phone's
 * immediate 200/401, then framed access units written by one writer thread from a bounded queue.
 *
 * <p>Backpressure: P-frames depend on earlier frames, so the newest unit can't simply be dropped
 * the way a JPEG preview frame is. When the oldest queued unit is older than {@code maxQueueAgeMs}
 * (or the queue is full, or a unit is oversized), the whole queue is dropped, every unit up to the
 * next config or keyframe is dropped too, and {@link Events#onKeyframeNeeded()} asks for a keyframe.
 */
public class PreviewStreamTransport implements StreamTransport {

    private static final class Queued {
        final AccessUnit unit;
        final long queuedAtMs;

        Queued(AccessUnit unit, long queuedAtMs) {
            this.unit = unit;
            this.queuedAtMs = queuedAtMs;
        }
    }

    private final PreviewStreamWire.Endpoint endpoint;
    private final String token;
    private final Clock clock;
    private final long maxQueueAgeMs;
    private final int maxQueuedUnits;
    private final Events events;

    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    // Guarded by queue.
    private boolean waitingForKeyframe = true;
    private volatile boolean closed;
    private volatile Socket socket;

    public PreviewStreamTransport(String url, String token, Clock clock, Events events) {
        this(url, token, clock, AsgConstants.CAMERA_PREVIEW_STREAM_MAX_QUEUE_AGE_MS,
                AsgConstants.CAMERA_PREVIEW_STREAM_MAX_QUEUED_UNITS, events);
    }

    PreviewStreamTransport(String url, String token, Clock clock, long maxQueueAgeMs, int maxQueuedUnits,
            Events events) {
        this.endpoint = PreviewStreamWire.endpoint(url);
        this.token = token;
        this.clock = clock;
        this.maxQueueAgeMs = maxQueueAgeMs;
        this.maxQueuedUnits = maxQueuedUnits;
        this.events = events;
    }

    @Override
    public void start() {
        Thread writer = new Thread(this::run, "PreviewStreamWriter");
        writer.setDaemon(true);
        writer.start();
    }

    @Override
    public void offer(AccessUnit unit) {
        int dropped = 0;
        boolean needKeyframe = false;
        synchronized (queue) {
            if (closed) {
                return;
            }
            long now = clock.nowMs();
            Queued oldest = queue.peekFirst();
            boolean backedUp = oldest != null
                    && (now - oldest.queuedAtMs > maxQueueAgeMs || queue.size() >= maxQueuedUnits);
            boolean oversized = unit.payload.length > PreviewStreamWire.MAX_UNIT_BYTES;
            if (backedUp || oversized) {
                dropped += queue.size();
                queue.clear();
                waitingForKeyframe = true;
                needKeyframe = true;
            }
            if (oversized || (waitingForKeyframe && !unit.isConfig() && !unit.isKeyframe())) {
                dropped++;
            } else {
                waitingForKeyframe = false;
                queue.addLast(new Queued(unit, now));
                queue.notifyAll();
            }
        }
        if (dropped > 0) {
            events.onDropped(dropped);
        }
        if (needKeyframe) {
            events.onKeyframeNeeded();
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (queue) {
            queue.clear();
            queue.notifyAll();
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** Units waiting to be written, oldest first. Tests only. */
    List<AccessUnit> queuedUnits() {
        synchronized (queue) {
            List<AccessUnit> units = new ArrayList<>();
            for (Queued q : queue) {
                units.add(q.unit);
            }
            return units;
        }
    }

    private void run() {
        try (Socket s = new Socket()) {
            socket = s;
            if (closed) {
                return;
            }
            s.connect(new InetSocketAddress(endpoint.host, endpoint.port),
                    (int) AsgConstants.CAMERA_PREVIEW_HTTP_CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setSoTimeout((int) AsgConstants.CAMERA_PREVIEW_HTTP_READ_TIMEOUT_MS);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            out.write(PreviewStreamWire.requestHead(endpoint, token));
            out.flush();
            int status = PreviewStreamWire.readStatus(s.getInputStream());
            if (closed) {
                return;
            }
            if (status == 401) {
                events.onUnauthorized();
                return;
            }
            if (status != 200) {
                events.onFailure("http_" + status);
                return;
            }
            events.onConnected();
            while (true) {
                Queued next;
                synchronized (queue) {
                    while (!closed && queue.isEmpty()) {
                        queue.wait(250);
                    }
                    if (closed) {
                        return;
                    }
                    next = queue.pollFirst();
                }
                out.write(PreviewStreamWire.unitHeader(next.unit));
                out.write(next.unit.payload);
                out.flush();
                events.onDelivered(next.unit);
            }
        } catch (IOException e) {
            if (!closed) {
                events.onFailure(e.getClass().getSimpleName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
