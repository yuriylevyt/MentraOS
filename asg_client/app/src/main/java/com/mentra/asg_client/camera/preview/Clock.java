package com.mentra.asg_client.camera.preview;

import android.os.SystemClock;

/** Monotonic time source, injected so tests can drive it deterministically. */
public interface Clock {
    long nowMs();

    class SystemMillisClock implements Clock {
        @Override
        public long nowMs() {
            return SystemClock.elapsedRealtime();
        }
    }
}
