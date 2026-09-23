package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-glasses check of the H.264 source: Camera2 into the MTK encoder for about 4 s, with one
 * keyframe request in the middle. Needs the glasses on adb with the camera idle.
 */
@RunWith(AndroidJUnit4.class)
public class Camera2H264SourceDeviceTest {
    private static final String TAG = "CameraPreviewH264";

    @Test
    public void encodesAConfigUnitThenKeyframesAndFrames() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        awaitStableCamera(context);
        List<AccessUnit> units = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> error = new AtomicReference<>();
        Camera2H264Source source = new Camera2H264Source(context);
        PreviewConfig config = new PreviewConfig(
                1280, 720, 60, 100L, "http://127.0.0.1:9/stream", "t", PreviewFormat.H264, 1500, 1000L);

        source.start(config, new AccessUnitSource.UnitListener() {
            @Override
            public void onUnit(AccessUnit unit) {
                units.add(unit);
            }

            @Override
            public void onError(String reason) {
                error.set(reason);
            }
        });
        Thread.sleep(2500);
        source.requestKeyframe();
        Thread.sleep(1500);
        source.stop();

        List<AccessUnit> seen;
        synchronized (units) {
            seen = new ArrayList<>(units);
        }
        long keyframes = seen.stream().filter(AccessUnit::isKeyframe).count();
        long configs = seen.stream().filter(AccessUnit::isConfig).count();
        long bytes = seen.stream().mapToLong(u -> u.payload.length).sum();
        Log.i(TAG, "device test units=" + seen.size() + " keyframes=" + keyframes
                + " configs=" + configs + " bytes=" + bytes);

        assertNull("source error", error.get());
        assertTrue("at least 20 units, got " + seen.size(), seen.size() >= 20);
        assertTrue("first unit is a config unit", seen.get(0).isConfig());
        assertTrue("second unit is a keyframe", seen.get(1).isKeyframe());
        assertTrue("at least 3 keyframes (1 s GOP + one request), got " + keyframes, keyframes >= 3);
        assertEquals("one config unit per keyframe", keyframes, configs);
    }

    /**
     * The instrumentation run starts a fresh asg_client process, and for ~25 s after that the app
     * restarts camerahalserver up to four times: the saved FOV and camera tuning applied on service
     * start, then a connected phone's settings sync (camera_fov_setting) and the tuning re-applied
     * after it. Each restart unregisters the camera for about a second, and CameraManager's
     * availability callback does not report it. Wait until 35 s after the process started, then
     * require the camera to be listed.
     */
    private static void awaitStableCamera(Context context) throws Exception {
        long settleAt = Process.getStartElapsedRealtime() + 35_000L;
        long wait = settleAt - SystemClock.elapsedRealtime();
        if (wait > 0) {
            Log.i(TAG, "device test waiting " + wait + " ms for the startup HAL restarts");
            Thread.sleep(wait);
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        while (manager.getCameraIdList().length == 0) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new AssertionError("camera not registered 45 s after process start");
            }
            Thread.sleep(200);
        }
    }
}
