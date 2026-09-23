package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OkHttpPreviewFrameSinkTest {

    private MockWebServer server;
    private OkHttpPreviewFrameSink sink;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        sink = new OkHttpPreviewFrameSink();
        sink.configure(server.url("/frame").toString(), "secret-token");
    }

    @After
    public void tearDown() throws Exception {
        sink.close();
        server.shutdown();
    }

    private CountDownLatch enqueueAndAwait(PreviewFrame frame, AtomicBoolean resultHolder) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        sink.trySend(frame, success -> {
            resultHolder.set(success);
            latch.countDown();
        });
        return latch;
    }

    @Test
    public void sendsHeadersBodyAndMethod() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        byte[] jpeg = new byte[]{1, 2, 3, 4};
        PreviewFrame frame = new PreviewFrame(jpeg, 7L, 12345L);
        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = enqueueAndAwait(frame, result);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(result.get());

        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("Bearer secret-token", request.getHeader("Authorization"));
        assertEquals("7", request.getHeader("X-Frame-Id"));
        assertEquals("12345", request.getHeader("X-Capture-Time-Ms"));
        assertEquals("image/jpeg", request.getHeader("Content-Type"));
        assertEquals(jpeg.length, request.getBodySize());
        assertTrue(java.util.Arrays.equals(jpeg, request.getBody().readByteArray()));
    }

    @Test
    public void secondSendWhileFirstInFlightReturnsFalseAndNeverReachesServer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBodyDelay(2, TimeUnit.SECONDS));
        PreviewFrame frame1 = new PreviewFrame(new byte[]{1}, 1L, 1L);
        PreviewFrame frame2 = new PreviewFrame(new byte[]{2}, 2L, 2L);

        boolean firstAccepted = sink.trySend(frame1, success -> {
        });
        assertTrue(firstAccepted);

        boolean secondAccepted = sink.trySend(frame2, success -> {
        });
        assertFalse(secondAccepted);

        assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void serverErrorResponseYieldsFailureCallback() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        PreviewFrame frame = new PreviewFrame(new byte[]{1}, 1L, 1L);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch latch = enqueueAndAwait(frame, result);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertFalse(result.get());
    }

    @Test
    public void trySendWorksAgainAfterAResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
        PreviewFrame frame1 = new PreviewFrame(new byte[]{1}, 1L, 1L);
        PreviewFrame frame2 = new PreviewFrame(new byte[]{2}, 2L, 2L);

        AtomicBoolean result1 = new AtomicBoolean(false);
        CountDownLatch latch1 = enqueueAndAwait(frame1, result1);
        assertTrue(latch1.await(3, TimeUnit.SECONDS));
        assertTrue(result1.get());
        assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null);

        AtomicBoolean result2 = new AtomicBoolean(false);
        CountDownLatch latch2 = enqueueAndAwait(frame2, result2);
        assertTrue(latch2.await(3, TimeUnit.SECONDS));
        assertTrue(result2.get());
        assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null);
        assertEquals(2, server.getRequestCount());
    }
}
