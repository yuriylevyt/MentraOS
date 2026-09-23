package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CapturePacerTest {

    /**
     * Simulates the source's capture loop: checks at every ms, a capture delivers
     * {@code latencyMs} after it was issued. Returns the number of captures issued.
     */
    private static int simulate(long intervalMs, long latencyMs, long durationMs) {
        return simulate(intervalMs, latencyMs, durationMs, 1);
    }

    private static int simulate(long intervalMs, long latencyMs, long durationMs, int maxOutstanding) {
        long t = 0;
        CapturePacer pacer = new CapturePacer(intervalMs, t, maxOutstanding);
        java.util.ArrayDeque<Long> deliverAt = new java.util.ArrayDeque<>();
        int issued = 0;
        for (; t < durationMs; t++) {
            while (!deliverAt.isEmpty() && t >= deliverAt.peekFirst()) {
                pacer.onDelivered(t);
                deliverAt.pollFirst();
            }
            if (pacer.tryIssue(t)) {
                issued++;
                deliverAt.addLast(t + latencyMs);
                assertTrue("in flight " + deliverAt.size(), deliverAt.size() <= maxOutstanding);
            }
        }
        return issued;
    }

    @Test
    public void fiveInFlightReachTenPerSecondDespite410msCaptureLatency() {
        // Measured on the MT6761: ~410 ms from capture() to image at [15,15].
        assertTrue(simulate(100, 410, 10_000, 1) <= 25); // one at a time: ~2.4/s
        int n = simulate(100, 410, 10_000, 5);
        assertTrue("issued=" + n, n >= 99 && n <= 101);
    }

    @Test
    public void manyInFlightNeverExceedTheIntervalRate() {
        int n = simulate(1000, 410, 10_000, 5);
        assertEquals(10, n);
    }

    @Test
    public void fastCapturesAt100msGiveTenPerSecond() {
        int n = simulate(100, 60, 10_000);
        assertTrue("issued=" + n, n >= 99 && n <= 101);
    }

    @Test
    public void captureLatencySlightlyAboveIntervalDoesNotHalveTheRate() {
        // 130 ms latency at a 100 ms interval: the skipped tick fires on delivery, so the rate
        // is ~1/130ms (~7.7/s), not 1/200ms.
        int n = simulate(100, 130, 10_000);
        assertTrue("issued=" + n, n >= 70 && n <= 78);
    }

    @Test
    public void oneSecondIntervalGivesOnePerSecond() {
        int n = simulate(1000, 120, 10_000);
        assertEquals(10, n);
    }

    @Test
    public void tickWhileOutstandingIsSkippedAndCounted() {
        CapturePacer pacer = new CapturePacer(100, 0);
        assertTrue(pacer.tryIssue(0));
        assertFalse(pacer.tryIssue(100)); // due, but previous capture still outstanding
        assertEquals(1, pacer.takeSkipped());
        assertEquals(150, pacer.onDelivered(150));
        assertTrue(pacer.tryIssue(150)); // overdue -> fires as soon as the previous delivered
    }

    @Test
    public void notDueYetIsNotASkip() {
        CapturePacer pacer = new CapturePacer(100, 0);
        assertTrue(pacer.tryIssue(0));
        pacer.onDelivered(40);
        assertFalse(pacer.tryIssue(50));
        assertEquals(0, pacer.takeSkipped());
        assertEquals(50, pacer.delayUntilNextCheckMs(50));
    }

    @Test
    public void longStallDoesNotCauseCatchUpBurst() {
        CapturePacer pacer = new CapturePacer(100, 0);
        assertTrue(pacer.tryIssue(0));
        pacer.onDelivered(10);
        // Nothing for 1 s (e.g. HAL stall), then the loop resumes.
        assertTrue(pacer.tryIssue(1_000));
        pacer.onDelivered(1_010);
        assertFalse("no immediate back-to-back catch-up", pacer.tryIssue(1_010));
    }

    @Test
    public void captureThatNeverDeliversIsAbandonedAfterStuckTimeout() {
        CapturePacer pacer = new CapturePacer(100, 0);
        assertTrue(pacer.tryIssue(0));
        assertFalse(pacer.tryIssue(1_999));
        assertTrue(pacer.tryIssue(2_000));
    }

    @Test
    public void lateDeliveryWithNothingOutstandingReturnsMinusOne() {
        CapturePacer pacer = new CapturePacer(100, 0);
        assertEquals(-1, pacer.onDelivered(5));
    }
}
