package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class H264PreviewSessionTest {

    private static final PreviewConfig CONFIG = new PreviewConfig(
            1280, 720, 60, 100L, "http://192.168.0.130:5000/stream", "tok", PreviewFormat.H264, 1500, 1000L);
    private static final AccessUnit P = new AccessUnit(new byte[] {1}, 1L, 0);

    private static final class FakeSource implements AccessUnitSource {
        UnitListener listener;
        int starts;
        int stops;
        int keyframeRequests;

        @Override public void start(PreviewConfig config, UnitListener listener) { this.listener = listener; starts++; }
        @Override public void requestKeyframe() { keyframeRequests++; }
        @Override public void stop() { stops++; }
    }

    private static final class FakeTransport implements StreamTransport {
        final Events events;
        final List<AccessUnit> offered = new ArrayList<>();
        boolean started;
        boolean closed;

        FakeTransport(Events events) { this.events = events; }

        @Override public void start() { started = true; }
        @Override public void offer(AccessUnit unit) { offered.add(unit); }
        @Override public void close() { closed = true; }
    }

    private static final class RecordingListener implements CameraPreviewSession.Listener {
        int started;
        final List<String> stopped = new ArrayList<>();
        final List<int[]> stats = new ArrayList<>();

        @Override public void onStarted() { started++; }
        @Override public void onStopped(String reason) { stopped.add(reason); }
        @Override public void onStats(int sent, int dropped, int failed) { stats.add(new int[] {sent, dropped, failed}); }
    }

    private final long[] now = {0L};
    private final List<FakeTransport> transports = new ArrayList<>();
    private FakeSource source;
    private RecordingListener listener;
    private H264PreviewSession session;

    @Before
    public void setUp() {
        source = new FakeSource();
        listener = new RecordingListener();
        session = new H264PreviewSession(
                source,
                (config, events) -> {
                    FakeTransport t = new FakeTransport(events);
                    transports.add(t);
                    return t;
                },
                () -> now[0],
                listener);
    }

    private FakeTransport current() {
        return transports.get(transports.size() - 1);
    }

    @Test
    public void start_opensATransportStartsTheSourceAndForwardsUnits() {
        session.start(CONFIG);
        source.listener.onUnit(P);

        assertEquals(1, transports.size());
        assertTrue(current().started);
        assertEquals(1, source.starts);
        assertEquals(1, listener.started);
        assertTrue(session.isActive());
        assertEquals(1, current().offered.size());
        assertSame(P, current().offered.get(0));
    }

    @Test
    public void keyframeNeeded_asksTheEncoder_andDropsShowInStats() {
        session.start(CONFIG);
        current().events.onConnected();
        current().events.onKeyframeNeeded();
        current().events.onDropped(3);
        current().events.onDelivered(P);

        now[0] = 2_000L;
        session.tick();

        assertEquals(1, source.keyframeRequests);
        assertEquals(1, listener.stats.size());
        assertEquals(1, listener.stats.get(0)[0]);
        assertEquals(3, listener.stats.get(0)[1]);
        assertEquals(0, listener.stats.get(0)[2]);
        assertEquals(List.of(), listener.stopped);
    }

    @Test
    public void keepsRunningPastTenMinutes_whileUnitsAreDelivered() {
        session.start(CONFIG);
        current().events.onConnected();
        for (long t = 1_000L; t <= 720_000L; t += 1_000L) { // past the old 600 s cap
            now[0] = t;
            current().events.onDelivered(P);
            session.tick();
        }

        assertEquals(List.of(), listener.stopped);
        assertTrue(session.isActive());
    }

    @Test
    public void a401StopsAtOnceWithUnauthorized() {
        session.start(CONFIG);
        current().events.onUnauthorized();

        assertEquals(List.of("unauthorized"), listener.stopped);
        assertEquals(1, source.stops);
        assertTrue(current().closed);
        assertFalse(session.isActive());
    }

    @Test
    public void failures_reconnectOnTickWithAKeyframe_andStopAfterFiveSeconds() {
        session.start(CONFIG);
        current().events.onFailure("ConnectException"); // t=0, streak starts
        assertTrue(transports.get(0).closed);

        for (long t = 1_000L; t <= 4_000L; t += 1_000L) {
            now[0] = t;
            session.tick(); // opens a new transport
            assertTrue(current().started);
            current().events.onFailure("ConnectException");
        }
        assertEquals(5, transports.size());
        assertEquals(4, source.keyframeRequests);
        assertEquals(List.of(), listener.stopped);

        now[0] = 5_000L;
        session.tick();
        assertEquals(List.of("post_failures"), listener.stopped);
    }

    @Test
    public void aConnectionThatDeliversNothingForFiveSecondsStops_andStaleCallbacksAreIgnored() {
        session.start(CONFIG);
        FakeTransport first = current();
        first.events.onFailure("SocketException");
        now[0] = 1_000L;
        session.tick();
        current().events.onConnected(); // resets the failure streak
        first.events.onUnauthorized(); // from the replaced transport: ignored

        now[0] = 5_900L;
        session.tick();
        assertEquals(List.of(), listener.stopped);

        now[0] = 6_000L;
        session.tick();
        assertEquals(List.of("post_failures"), listener.stopped);
    }
}
