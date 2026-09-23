package com.mentra.asg_client.service.core.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mentra.asg_client.camera.CameraNeoService;
import com.mentra.asg_client.camera.model.QueuedPhotoRequest;
import com.mentra.asg_client.camera.model.QueuedPhotoRequestQueue;
import com.mentra.asg_client.camera.preview.CameraPreviewFrameSource;
import com.mentra.asg_client.camera.preview.CameraPreviewSession;
import com.mentra.asg_client.camera.preview.PreviewConfig;
import com.mentra.asg_client.camera.preview.PreviewFrameSink;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CameraPreviewCommandHandlerTest {

    private static final String URL = "http://192.168.1.10:8765/frame";
    private static final String TOKEN = "secret";

    /** Records start/stop calls; start succeeds unless {@link #failStart} is set. */
    private static class FakeSource implements CameraPreviewFrameSource {
        int starts;
        int stops;
        int lastWidth, lastHeight, lastQuality;
        boolean failStart;

        @Override
        public void start(int width, int height, int quality, long intervalMs, FrameListener listener)
                throws Exception {
            if (failStart) throw new IllegalStateException("boom");
            starts++;
            lastWidth = width;
            lastHeight = height;
            lastQuality = quality;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static class FakeScheduler implements CameraPreviewCommandHandler.TickScheduler {
        final List<Runnable> scheduled = new ArrayList<>();
        int cancels;
        long lastPeriodMs;

        @Override
        public Runnable schedule(Runnable task, long periodMs) {
            scheduled.add(task);
            lastPeriodMs = periodMs;
            return () -> cancels++;
        }
    }

    private ICommunicationManager comm;
    private FakeScheduler scheduler;
    private final List<FakeSource> sources = new ArrayList<>();
    private boolean busy;
    private int idleReleases;
    private CameraPreviewCommandHandler handler;

    @Before
    public void setUp() {
        CameraPreviewCommandHandler.resetForTest();
        comm = mock(ICommunicationManager.class);
        when(comm.sendBluetoothResponse(any())).thenReturn(true);
        scheduler = new FakeScheduler();
        busy = false;
        idleReleases = 0;
        handler =
                new CameraPreviewCommandHandler(
                        comm,
                        listener -> {
                            FakeSource source = new FakeSource();
                            sources.add(source);
                            return new CameraPreviewSession(
                                    source, mock(PreviewFrameSink.class), () -> 0L, listener);
                        },
                        new CameraPreviewCommandHandler.CameraGate() {
                            @Override
                            public boolean isBusy() {
                                return busy;
                            }

                            @Override
                            public void releaseIdleCamera() {
                                idleReleases++;
                            }
                        },
                        scheduler);
    }

    @After
    public void tearDown() {
        CameraPreviewCommandHandler.resetForTest();
    }

    private static JSONObject startParams() throws Exception {
        return new JSONObject().put("url", URL).put("token", TOKEN);
    }

    private List<JSONObject> sent() {
        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(comm, atLeastOnce()).sendBluetoothResponse(captor.capture());
        return captor.getAllValues();
    }

    @Test
    public void supportsStartAndStop() {
        assertThat(handler.getSupportedCommandTypes())
                .containsExactlyInAnyOrder("start_camera_preview", "stop_camera_preview");
    }

    @Test
    public void start_whenPhotoRequestPending_repliesCameraBusyAndCreatesNoSession()
            throws Exception {
        // Locks the device bug: take_photo enqueues into the global queue before
        // CameraNeoService's instance/shot-state reflects it (queued, or camera still opening),
        // so isCameraInUse() alone would say "idle" here. Exercise the *real* DefaultCameraGate
        // (via the public constructor) against CameraNeoService's real static queue, not the
        // fake CameraGate seam used by the other tests.
        QueuedPhotoRequest pending =
                new QueuedPhotoRequest("/tmp/pending.jpg", "medium", false, true, null, null);
        QueuedPhotoRequestQueue.getInstance().offer(pending);
        try {
            CameraPreviewCommandHandler realGateHandler =
                    new CameraPreviewCommandHandler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            comm);

            boolean handled =
                    realGateHandler.handleCommand("start_camera_preview", startParams());

            assertThat(handled).isFalse();
            assertThat(CameraPreviewCommandHandler.isActive()).isFalse();
            List<JSONObject> msgs = sent();
            assertThat(msgs).hasSize(1);
            assertThat(msgs.get(0).getString("status")).isEqualTo("camera_busy");
        } finally {
            QueuedPhotoRequestQueue.getInstance().failAllPending("test-isolation");
        }
    }

    @Test
    public void start_whenCameraBusy_repliesCameraBusyAndCreatesNoSession() throws Exception {
        busy = true;

        boolean handled = handler.handleCommand("start_camera_preview", startParams());

        assertThat(handled).isFalse();
        assertThat(sources).isEmpty();
        assertThat(CameraPreviewCommandHandler.isActive()).isFalse();
        List<JSONObject> msgs = sent();
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getString("type")).isEqualTo("camera_preview_status");
        assertThat(msgs.get(0).getString("status")).isEqualTo("camera_busy");
        assertThat(msgs.get(0).has("timestamp")).isTrue();
    }

    @Test
    public void start_whenIdle_startsSessionAndSendsStartedWithEffectiveParams() throws Exception {
        boolean handled = handler.handleCommand("start_camera_preview", startParams());

        assertThat(handled).isTrue();
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).starts).isEqualTo(1);
        assertThat(sources.get(0).lastWidth).isEqualTo(1280);
        assertThat(sources.get(0).lastHeight).isEqualTo(720);
        assertThat(sources.get(0).lastQuality).isEqualTo(60);
        assertThat(idleReleases).isEqualTo(1);
        assertThat(CameraPreviewCommandHandler.isActive()).isTrue();
        assertThat(scheduler.scheduled).hasSize(1);
        assertThat(scheduler.lastPeriodMs).isEqualTo(1000L);

        JSONObject started = sent().get(0);
        assertThat(started.getString("type")).isEqualTo("camera_preview_status");
        assertThat(started.getString("status")).isEqualTo("started");
        assertThat(started.getLong("intervalMs")).isEqualTo(100L);
        assertThat(started.getInt("width")).isEqualTo(1280);
        assertThat(started.getInt("height")).isEqualTo(720);
        assertThat(started.getInt("quality")).isEqualTo(60);
        assertThat(started.has("timestamp")).isTrue();
    }

    @Test
    public void stop_whenActive_sendsStoppedRequestedAndReleasesCamera() throws Exception {
        handler.handleCommand("start_camera_preview", startParams());

        boolean handled = handler.handleCommand("stop_camera_preview", new JSONObject());

        assertThat(handled).isTrue();
        assertThat(sources.get(0).stops).isEqualTo(1);
        assertThat(scheduler.cancels).isEqualTo(1);
        assertThat(CameraPreviewCommandHandler.isActive()).isFalse();
        JSONObject last = sent().get(sent().size() - 1);
        assertThat(last.getString("status")).isEqualTo("stopped");
        assertThat(last.getString("reason")).isEqualTo("requested");
        assertThat(last.has("timestamp")).isTrue();
    }

    @Test
    public void start_whileActive_restartsWithNewParams() throws Exception {
        handler.handleCommand("start_camera_preview", startParams());

        handler.handleCommand("start_camera_preview", startParams().put("intervalMs", 1000));

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).stops).isEqualTo(1);
        assertThat(sources.get(1).starts).isEqualTo(1);
        assertThat(CameraPreviewCommandHandler.isActive()).isTrue();
        List<JSONObject> msgs = sent();
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(1).getString("status")).isEqualTo("stopped");
        assertThat(msgs.get(1).getString("reason")).isEqualTo("restarted");
        assertThat(msgs.get(2).getString("status")).isEqualTo("started");
        assertThat(msgs.get(2).getLong("intervalMs")).isEqualTo(1000L);
    }

    @Test
    public void start_withMissingOrNonHttpUrl_repliesInvalidParams() throws Exception {
        for (JSONObject params :
                new JSONObject[] {
                    new JSONObject().put("token", TOKEN),
                    new JSONObject().put("url", "rtmp://x/y").put("token", TOKEN),
                    new JSONObject().put("url", "").put("token", TOKEN),
                    new JSONObject().put("url", URL),
                    new JSONObject().put("url", URL).put("token", "")
                }) {
            assertThat(handler.handleCommand("start_camera_preview", params)).isFalse();
        }

        assertThat(sources).isEmpty();
        List<JSONObject> msgs = sent();
        assertThat(msgs).hasSize(5);
        for (JSONObject msg : msgs) {
            assertThat(msg.getString("type")).isEqualTo("camera_preview_status");
            assertThat(msg.getString("status")).isEqualTo("stopped");
            assertThat(msg.getString("reason")).isEqualTo("invalid_params");
            assertThat(msg.has("timestamp")).isTrue();
        }
    }

    @Test
    public void parseConfig_clampsIntervalAndQuality() throws Exception {
        assertThat(CameraPreviewCommandHandler.parseConfig(startParams().put("intervalMs", 0)).intervalMs)
                .isEqualTo(50L);
        assertThat(CameraPreviewCommandHandler.parseConfig(startParams().put("intervalMs", 6000)).intervalMs)
                .isEqualTo(5000L);
        assertThat(CameraPreviewCommandHandler.parseConfig(startParams()).intervalMs).isEqualTo(100L);
        assertThat(CameraPreviewCommandHandler.parseConfig(startParams().put("quality", 5)).quality)
                .isEqualTo(30);
        assertThat(CameraPreviewCommandHandler.parseConfig(startParams().put("quality", 100)).quality)
                .isEqualTo(95);

        PreviewConfig custom =
                CameraPreviewCommandHandler.parseConfig(
                        startParams().put("width", 640).put("height", 480).put("quality", 80));
        assertThat(custom.width).isEqualTo(640);
        assertThat(custom.height).isEqualTo(480);
        assertThat(custom.quality).isEqualTo(80);
        assertThat(custom.url).isEqualTo(URL);
        assertThat(custom.token).isEqualTo(TOKEN);
        assertThat(CameraPreviewCommandHandler.parseConfig(null)).isNull();
    }

    @Test
    public void stopIfActive_whenIdle_isNoOp() {
        CameraPreviewCommandHandler.stopIfActive("preempted");

        verify(comm, never()).sendBluetoothResponse(any());
        assertThat(scheduler.cancels).isZero();
    }

    @Test
    public void stopIfActive_whenActive_stopsSynchronouslyWithReason() throws Exception {
        handler.handleCommand("start_camera_preview", startParams());

        CameraPreviewCommandHandler.stopIfActive("preempted");

        assertThat(sources.get(0).stops).isEqualTo(1);
        assertThat(CameraPreviewCommandHandler.isActive()).isFalse();
        JSONObject last = sent().get(sent().size() - 1);
        assertThat(last.getString("status")).isEqualTo("stopped");
        assertThat(last.getString("reason")).isEqualTo("preempted");
    }

    @Test
    public void cameraErrorOnStart_sendsStoppedCameraErrorAndClearsState() throws Exception {
        CameraPreviewCommandHandler failing =
                new CameraPreviewCommandHandler(
                        comm,
                        listener -> {
                            FakeSource source = new FakeSource();
                            source.failStart = true;
                            return new CameraPreviewSession(
                                    source, mock(PreviewFrameSink.class), () -> 0L, listener);
                        },
                        new CameraPreviewCommandHandler.CameraGate() {
                            @Override
                            public boolean isBusy() {
                                return false;
                            }

                            @Override
                            public void releaseIdleCamera() {}
                        },
                        scheduler);

        failing.handleCommand("start_camera_preview", startParams());

        assertThat(CameraPreviewCommandHandler.isActive()).isFalse();
        assertThat(scheduler.cancels).isEqualTo(scheduler.scheduled.size());
        JSONObject last = sent().get(sent().size() - 1);
        assertThat(last.getString("status")).isEqualTo("stopped");
        assertThat(last.getString("reason")).isEqualTo("camera_error");
    }

    @Test
    public void tick_pumpsSessionStats() throws Exception {
        long[] now = {0L};
        CameraPreviewCommandHandler ticking =
                new CameraPreviewCommandHandler(
                        comm,
                        listener ->
                                new CameraPreviewSession(
                                        new FakeSource(),
                                        mock(PreviewFrameSink.class),
                                        () -> now[0],
                                        listener),
                        new CameraPreviewCommandHandler.CameraGate() {
                            @Override
                            public boolean isBusy() {
                                return false;
                            }

                            @Override
                            public void releaseIdleCamera() {}
                        },
                        scheduler);
        ticking.handleCommand("start_camera_preview", startParams());

        now[0] = 2_000L;
        scheduler.scheduled.get(0).run();

        JSONObject last = sent().get(sent().size() - 1);
        assertThat(last.getString("status")).isEqualTo("stats");
        assertThat(last.getInt("sent")).isZero();
        assertThat(last.getInt("dropped")).isZero();
        assertThat(last.getInt("failed")).isZero();
        assertThat(last.has("timestamp")).isTrue();
    }
}
