package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CameraPreviewSessionTest {

    /** Deterministic, hand-advanced clock; never touches wall/monotonic time. */
    private static final class FakeClock implements Clock {
        long nowMs;

        @Override
        public long nowMs() {
            return nowMs;
        }
    }

    /** Fake camera source: test drives frames by calling deliverFrame()/deliverError(). */
    private static final class FakeSource implements CameraPreviewFrameSource {
        FrameListener listener;
        boolean started;
        boolean stopped;
        long intervalMs;

        @Override
        public void start(int width, int height, int quality, long intervalMs, FrameListener listener) {
            this.listener = listener;
            this.started = true;
            this.intervalMs = intervalMs;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        void deliverFrame(byte[] jpegBytes, long captureTimeMs) {
            listener.onFrame(jpegBytes, captureTimeMs);
        }

        void deliverError(String reason) {
            listener.onError(reason);
        }
    }

    /** Fake sink: scripted busy/success/fail, captures pending callbacks so the test can complete them later. */
    private static final class FakeSink implements PreviewFrameSink {
        boolean busy;
        boolean closed;
        final Deque<PreviewFrameSink.Callback> pending = new ArrayDeque<>();
        final List<PreviewFrame> sent = new ArrayList<>();

        @Override
        public boolean trySend(PreviewFrame frame, Callback callback) {
            if (busy) {
                return false;
            }
            busy = true;
            sent.add(frame);
            pending.add(callback);
            return true;
        }

        void completeOldest(boolean success) {
            PreviewFrameSink.Callback callback = pending.poll();
            busy = false;
            if (callback != null) {
                callback.onResult(success);
            }
        }

        @Override
        public void configure(String url, String token) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Sink whose callback fires SYNCHRONOUSLY inside trySend, before trySend returns true —
     * mirrors a real OkHttp dispatcher thread winning the race against the calling thread on an
     * immediate failure (e.g. ECONNREFUSED).
     */
    private static final class SyncCallbackSink implements PreviewFrameSink {
        private final Deque<Boolean> scriptedResults;

        SyncCallbackSink(Boolean... results) {
            scriptedResults = new ArrayDeque<>(java.util.Arrays.asList(results));
        }

        @Override
        public boolean trySend(PreviewFrame frame, Callback callback) {
            boolean result = scriptedResults.isEmpty() ? true : scriptedResults.poll();
            callback.onResult(result);
            return true;
        }

        @Override
        public void configure(String url, String token) {
        }

        @Override
        public void close() {
        }
    }

    private FakeClock clock;
    private FakeSource source;
    private FakeSink sink;
    private List<String> stoppedReasons;
    private int startedCount;
    private List<int[]> stats; // {sent, dropped, failed}
    private CameraPreviewSession session;

    @Before
    public void setUp() {
        clock = new FakeClock();
        clock.nowMs = 1_000_000L;
        source = new FakeSource();
        sink = new FakeSink();
        stoppedReasons = new ArrayList<>();
        stats = new ArrayList<>();
        session = new CameraPreviewSession(source, sink, clock, new CameraPreviewSession.Listener() {
            @Override
            public void onStarted() {
                startedCount++;
            }

            @Override
            public void onStopped(String reason) {
                stoppedReasons.add(reason);
            }

            @Override
            public void onStats(int sent, int dropped, int failed) {
                stats.add(new int[]{sent, dropped, failed});
            }
        });
    }

    private PreviewConfig config(long intervalMs) {
        return new PreviewConfig(1280, 720, 60, intervalMs, "http://phone.local/frame", "tok");
    }

    @Test
    public void passesIntervalToSourceSoTheSourcePacesCaptures() {
        session.start(config(250));
        assertTrue(source.started);
        assertEquals(250L, source.intervalMs);
    }

    @Test
    public void forwardsEveryFrameTheSourceDeliversWhileSinkIsIdle() {
        // Pacing lives in the source (one capture per interval), so the session must not
        // second-guess timing: every delivered frame goes out when the sink is idle, even with
        // jitter that lands a frame slightly early.
        session.start(config(100));
        long t = clock.nowMs;
        long[] jitter = {100, 95, 105, 90, 110, 100, 99, 101, 97, 103};
        for (long step : jitter) {
            t += step;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t);
            sink.completeOldest(true);
        }
        assertEquals(10, sink.sent.size());
        assertEquals(10, sink.sent.get(9).frameId);
    }

    @Test
    public void frameWhileSinkBusyIsDroppedNotRetried() {
        session.start(config(100));
        clock.nowMs += 100;
        source.deliverFrame(new byte[]{1}, clock.nowMs); // goes in-flight, sink stays busy (no complete)
        assertEquals(1, sink.sent.size());

        clock.nowMs += 100;
        source.deliverFrame(new byte[]{2}, clock.nowMs); // eligible by due time, but sink busy -> dropped
        assertEquals(1, sink.sent.size());

        clock.nowMs += 2000; // trigger a stats emission to read dropped count
        session.tick();
        int[] last = stats.get(stats.size() - 1);
        assertEquals(1, last[1]); // dropped == 1
    }

    @Test
    public void fiveSecondsOfConsecutiveFailuresStopsWithPostFailures() {
        session.start(config(100));
        long t = clock.nowMs;
        for (int i = 0; i < 60; i++) {
            t += 100;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t);
            if (!sink.pending.isEmpty()) {
                sink.completeOldest(false);
            }
            if (!stoppedReasons.isEmpty()) {
                break;
            }
        }
        assertEquals(1, stoppedReasons.size());
        assertEquals("post_failures", stoppedReasons.get(0));
    }

    @Test
    public void successResetsTheFailureWindow() {
        session.start(config(100));
        long t = clock.nowMs;
        // fail for 4 seconds
        for (int i = 0; i < 40; i++) {
            t += 100;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t);
            if (!sink.pending.isEmpty()) {
                sink.completeOldest(false);
            }
        }
        assertEquals(List.of(), stoppedReasons);
        // a success resets the window
        t += 100;
        clock.nowMs = t;
        source.deliverFrame(new byte[]{1}, t);
        sink.completeOldest(true);
        // fail again for 4 more seconds -> should still not stop since window reset
        for (int i = 0; i < 40; i++) {
            t += 100;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t);
            if (!sink.pending.isEmpty()) {
                sink.completeOldest(false);
            }
        }
        assertEquals(List.of(), stoppedReasons);
    }

    @Test
    public void largeIntervalWithSlowSuccessfulPostsNeverStops() {
        // intervalMs=5000 with every POST succeeding after a 3000 ms delay must never trip the
        // failure window: the window is measured from the last failure streak / in-flight start,
        // not from "time since last success", so a healthy slow-but-successful preview at a long
        // interval must not stop itself.
        session.start(config(5000));
        long stepMs = 100;
        long delayMs = 3000;
        long start = clock.nowMs;
        long t = start;
        long endAt = clock.nowMs + 60_000;
        Long pendingCompletionAt = null;
        while (t < endAt) {
            t += stepMs;
            clock.nowMs = t;
            if (pendingCompletionAt != null && t >= pendingCompletionAt) {
                sink.completeOldest(true);
                pendingCompletionAt = null;
            }
            if ((t - start) % 5000 == 0) { // source delivers one frame per interval
                int before = sink.sent.size();
                source.deliverFrame(new byte[]{1}, t);
                if (sink.sent.size() > before) {
                    pendingCompletionAt = t + delayMs;
                }
            }
        }
        if (pendingCompletionAt != null) {
            clock.nowMs = pendingCompletionAt;
            sink.completeOldest(true);
        }
        assertEquals(List.of(), stoppedReasons);
        assertTrue("sent=" + sink.sent.size(), sink.sent.size() >= 11 && sink.sent.size() <= 13);
    }

    @Test
    public void singleFailureThenSuccessFiveSecondsLaterDoesNotStop() {
        session.start(config(5000));
        long s = clock.nowMs;

        source.deliverFrame(new byte[]{1}, s);
        assertEquals(1, sink.sent.size());
        sink.completeOldest(false);
        assertEquals(List.of(), stoppedReasons);

        clock.nowMs = s + 5000;
        source.deliverFrame(new byte[]{2}, s + 5000);
        assertEquals(2, sink.sent.size());
        sink.completeOldest(true);

        assertEquals(List.of(), stoppedReasons);
    }

    @Test
    public void twoConsecutiveFailuresFiveSecondsApartStops() {
        session.start(config(5000));
        long s = clock.nowMs;

        source.deliverFrame(new byte[]{1}, s);
        sink.completeOldest(false);
        assertEquals(List.of(), stoppedReasons);

        clock.nowMs = s + 5000;
        source.deliverFrame(new byte[]{2}, s + 5000);
        sink.completeOldest(false);

        assertEquals(1, stoppedReasons.size());
        assertEquals("post_failures", stoppedReasons.get(0));
    }

    @Test
    public void synchronousCallbackDuringTrySendDoesNotFalselyHang() {
        // The callback fires before trySend() returns to the caller, so a naive "mark in-flight
        // after trySend returns true" ordering leaves the in-flight marker stuck forever even
        // though nothing is actually outstanding -> false "hung sink" stop 5s later.
        FakeSource syncSource = new FakeSource();
        SyncCallbackSink syncSink = new SyncCallbackSink(false); // first call fails; rest succeed
        List<String> syncStoppedReasons = new ArrayList<>();
        CameraPreviewSession syncSession = new CameraPreviewSession(syncSource, syncSink, clock,
                new CameraPreviewSession.Listener() {
                    @Override
                    public void onStarted() {
                    }

                    @Override
                    public void onStopped(String reason) {
                        syncStoppedReasons.add(reason);
                    }

                    @Override
                    public void onStats(int sent, int dropped, int failed) {
                    }
                });

        syncSession.start(config(100));
        long t = clock.nowMs;
        long endAt = t + 10_000;
        while (t < endAt) {
            t += 100;
            clock.nowMs = t;
            syncSource.deliverFrame(new byte[]{1}, t);
        }

        assertTrue("stopped with: " + syncStoppedReasons, syncStoppedReasons.isEmpty());
    }

    @Test
    public void hungSinkStopsWithPostFailuresAtAboutFiveSeconds() {
        session.start(config(100));
        long t = clock.nowMs;
        sink.busy = false;
        for (int i = 0; i < 60; i++) {
            t += 100;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t); // first goes in-flight and never completes -> sink permanently busy
            if (!stoppedReasons.isEmpty()) {
                break;
            }
        }
        assertEquals(1, stoppedReasons.size());
        assertEquals("post_failures", stoppedReasons.get(0));
    }

    @Test
    public void keepsRunningPastTenMinutes() {
        session.start(config(100));
        long t = clock.nowMs;
        for (int i = 0; i < 7200; i++) { // 7200*100ms = 720s > the old 600s cap
            t += 100;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t);
            if (!sink.pending.isEmpty()) {
                sink.completeOldest(true);
            }
            session.tick();
        }
        assertEquals(List.of(), stoppedReasons);
        assertTrue(session.isActive());
    }

    @Test
    public void sourceErrorStopsWithCameraError() {
        session.start(config(100));
        source.deliverError("hal died");
        assertEquals(1, stoppedReasons.size());
        assertEquals("camera_error", stoppedReasons.get(0));
    }

    @Test
    public void sourceStartThrowingStopsWithCameraErrorAndNeverStarts() {
        CameraPreviewFrameSource throwingSource = new CameraPreviewFrameSource() {
            @Override
            public void start(int width, int height, int quality, long intervalMs, FrameListener listener) throws Exception {
                throw new java.io.IOException("camera busy");
            }

            @Override
            public void stop() {
            }
        };
        CameraPreviewSession throwingSession = new CameraPreviewSession(throwingSource, sink, clock,
                new CameraPreviewSession.Listener() {
                    @Override
                    public void onStarted() {
                        startedCount++;
                    }

                    @Override
                    public void onStopped(String reason) {
                        stoppedReasons.add(reason);
                    }

                    @Override
                    public void onStats(int sent, int dropped, int failed) {
                    }
                });

        throwingSession.start(config(100));

        assertEquals(0, startedCount);
        assertEquals(1, stoppedReasons.size());
        assertEquals("camera_error", stoppedReasons.get(0));
        assertFalse(throwingSession.isActive());
    }

    @Test
    public void stopIsIdempotentAndIgnoresFramesAndLateCallbacksAfterStop() {
        session.start(config(100));
        clock.nowMs += 100;
        source.deliverFrame(new byte[]{1}, clock.nowMs);
        PreviewFrameSink.Callback pendingCallback = sink.pending.peek();

        session.stop("requested");
        session.stop("requested"); // idempotent
        assertEquals(1, stoppedReasons.size());
        assertEquals("requested", stoppedReasons.get(0));
        assertFalse(session.isActive());

        int sentBefore = sink.sent.size();
        clock.nowMs += 100;
        source.deliverFrame(new byte[]{2}, clock.nowMs);
        assertEquals(sentBefore, sink.sent.size()); // no forwarding after stop

        // late callback after stop must not throw or re-stop
        pendingCallback.onResult(true);
        assertEquals(1, stoppedReasons.size());
    }

    @Test
    public void statsEmittedAboutEveryTwoSecondsWithCorrectCounts() {
        session.start(config(100));
        long frameIntervalMs = 100; // source paces itself to the 100ms interval
        long t = clock.nowMs;
        long endAt = clock.nowMs + 4_000;
        while (t < endAt) {
            t += frameIntervalMs;
            clock.nowMs = t;
            source.deliverFrame(new byte[]{1}, t);
            if (!sink.pending.isEmpty()) {
                sink.completeOldest(true);
            }
        }
        session.tick();
        assertFalse(stats.isEmpty());
        // At least two ~2s emissions expected over 4s of activity.
        assertTrue("emissions=" + stats.size(), stats.size() >= 2);
        int[] last = stats.get(stats.size() - 1);
        assertTrue("sent=" + last[0], last[0] >= 30 && last[0] <= 48);
        assertEquals(0, last[1]);
        assertEquals(0, last[2]);
    }
}
