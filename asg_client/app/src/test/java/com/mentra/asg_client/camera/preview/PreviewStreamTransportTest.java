package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class PreviewStreamTransportTest {

    private static final AccessUnit CONFIG = new AccessUnit(new byte[] {0, 0, 0, 1, 0x67}, 10L, AccessUnit.FLAG_CONFIG);
    private static final AccessUnit KEY = new AccessUnit(new byte[] {0, 0, 0, 1, 0x65, 7}, 10L, AccessUnit.FLAG_KEYFRAME);
    private static final AccessUnit P1 = new AccessUnit(new byte[] {0, 0, 0, 1, 0x41, 1}, 20L, 0);
    private static final AccessUnit P2 = new AccessUnit(new byte[] {0, 0, 0, 1, 0x41, 2}, 30L, 0);

    /** Records transport events; latches let socket tests wait for the writer thread. */
    private static final class RecordingEvents implements StreamTransport.Events {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch unauthorized = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch delivered2 = new CountDownLatch(2);
        final List<AccessUnit> delivered = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger dropped = new AtomicInteger();
        final AtomicInteger keyframeRequests = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<>();

        @Override public void onConnected() { connected.countDown(); }
        @Override public void onDelivered(AccessUnit unit) { delivered.add(unit); delivered2.countDown(); }
        @Override public void onDropped(int units) { dropped.addAndGet(units); }
        @Override public void onKeyframeNeeded() { keyframeRequests.incrementAndGet(); }
        @Override public void onUnauthorized() { unauthorized.countDown(); }
        @Override public void onFailure(String reason) { failure.set(reason); failed.countDown(); }
    }

    private final long[] now = {0L};
    private final Clock clock = () -> now[0];
    private ServerSocket server;
    private PreviewStreamTransport transport;

    @After
    public void tearDown() throws IOException {
        if (transport != null) transport.close();
        if (server != null) server.close();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString("US-ASCII").endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("closed mid-head");
            head.write(b);
        }
        return head.toString("US-ASCII");
    }

    private String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/stream";
    }

    @Test
    public void writesTheHead_thenFramedUnitsAfterA200() throws Exception {
        server = new ServerSocket(0);
        RecordingEvents events = new RecordingEvents();
        transport = new PreviewStreamTransport(url(), "tok", clock, events);
        transport.offer(CONFIG);
        transport.offer(KEY);
        transport.start();

        try (Socket phone = server.accept()) {
            String head = readHead(phone.getInputStream());
            phone.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            DataInputStream body = new DataInputStream(phone.getInputStream());
            int len1 = body.readInt();
            long ts1 = body.readLong();
            int flags1 = body.readUnsignedByte();
            body.skipBytes(len1);
            int len2 = body.readInt();
            long ts2 = body.readLong();
            int flags2 = body.readUnsignedByte();
            byte[] payload2 = new byte[len2];
            body.readFully(payload2);

            assertTrue(head.startsWith("POST /stream HTTP/1.1\r\n"));
            assertTrue(head.contains("Authorization: Bearer tok\r\n"));
            assertTrue(head.contains("X-Preview-Format: h264\r\n"));
            assertFalse(head.toLowerCase().contains("content-length"));
            assertFalse(head.toLowerCase().contains("transfer-encoding"));
            assertEquals(5, len1);
            assertEquals(10L, ts1);
            assertEquals(AccessUnit.FLAG_CONFIG, flags1);
            assertEquals(10L, ts2);
            assertEquals(AccessUnit.FLAG_KEYFRAME, flags2);
            assertEquals(7, payload2[5]);
            assertTrue(events.connected.await(2, TimeUnit.SECONDS));
            assertTrue(events.delivered2.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void a401ReportsUnauthorizedAndWritesNoUnits() throws Exception {
        server = new ServerSocket(0);
        RecordingEvents events = new RecordingEvents();
        transport = new PreviewStreamTransport(url(), "wrong", clock, events);
        transport.offer(CONFIG);
        transport.start();

        try (Socket phone = server.accept()) {
            readHead(phone.getInputStream());
            phone.getOutputStream().write(
                    "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            assertTrue(events.unauthorized.await(2, TimeUnit.SECONDS));
            assertEquals(-1, phone.getInputStream().read());
        }
        assertEquals(1L, events.connected.getCount());
        assertTrue(events.delivered.isEmpty());
    }

    @Test
    public void aRefusedConnectionReportsFailure() throws Exception {
        ServerSocket closed = new ServerSocket(0);
        int port = closed.getLocalPort();
        closed.close();
        RecordingEvents events = new RecordingEvents();
        transport = new PreviewStreamTransport("http://127.0.0.1:" + port + "/stream", "tok", clock, events);
        transport.start();

        assertTrue(events.failed.await(3, TimeUnit.SECONDS));
        assertEquals("ConnectException", events.failure.get());
    }

    @Test
    public void aBackedUpQueueIsDroppedUntilTheNextConfigOrKeyframe() {
        RecordingEvents events = new RecordingEvents();
        // Never started: nothing drains the queue, so it backs up.
        transport = new PreviewStreamTransport("http://127.0.0.1:9/stream", "tok", clock, 300L, 90, events);
        transport.offer(CONFIG);
        transport.offer(KEY);
        now[0] = 100L;
        transport.offer(P1);

        now[0] = 400L; // oldest queued unit is now 400 ms old
        transport.offer(P2);
        assertEquals(4, events.dropped.get());
        assertEquals(1, events.keyframeRequests.get());
        assertTrue(transport.queuedUnits().isEmpty());

        transport.offer(P1); // still waiting for a keyframe
        assertEquals(5, events.dropped.get());

        transport.offer(CONFIG);
        transport.offer(KEY);
        assertEquals(List.of(CONFIG, KEY), transport.queuedUnits());
        assertEquals(1, events.keyframeRequests.get());
    }

    @Test
    public void anOversizedUnitIsDroppedAndAsksForAKeyframe() {
        RecordingEvents events = new RecordingEvents();
        transport = new PreviewStreamTransport("http://127.0.0.1:9/stream", "tok", clock, 300L, 90, events);
        transport.offer(CONFIG);
        transport.offer(new AccessUnit(new byte[PreviewStreamWire.MAX_UNIT_BYTES + 1], 20L, AccessUnit.FLAG_KEYFRAME));

        assertEquals(2, events.dropped.get());
        assertEquals(1, events.keyframeRequests.get());
        assertTrue(transport.queuedUnits().isEmpty());
    }
}
