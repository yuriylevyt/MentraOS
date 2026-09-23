package com.mentra.asg_client.camera.preview;

import java.util.ArrayDeque;

/**
 * Pure pacing for on-demand JPEG captures: at most one capture per {@code intervalMs} on
 * average, and at most {@code maxOutstanding} captures in flight. A tick that lands while the
 * in-flight limit is reached is skipped; the capture then fires as soon as one delivers, so a
 * capture latency slightly above the interval doesn't halve the rate. Deliveries complete in
 * issue order (Camera2 returns captures in order). Not thread-safe; camera thread only.
 */
final class CapturePacer {

    private final long intervalMs;
    private final int maxOutstanding;
    private final long stuckAfterMs;
    private final ArrayDeque<Long> issuedAt = new ArrayDeque<>();
    private long nextDueMs;
    private int skipped;

    CapturePacer(long intervalMs, long startMs) {
        this(intervalMs, startMs, 1);
    }

    CapturePacer(long intervalMs, long startMs, int maxOutstanding) {
        this.intervalMs = Math.max(1, intervalMs);
        this.maxOutstanding = Math.max(1, maxOutstanding);
        // A capture whose image never arrives (HAL hiccup) must not wedge the loop forever.
        this.stuckAfterMs = Math.max(2_000L, 3 * this.intervalMs);
        this.nextDueMs = startMs;
    }

    /** Returns true if a capture should be issued now, and marks it outstanding. */
    boolean tryIssue(long nowMs) {
        while (!issuedAt.isEmpty() && nowMs - issuedAt.peekFirst() >= stuckAfterMs) {
            issuedAt.pollFirst();
        }
        if (nowMs < nextDueMs) {
            return false;
        }
        if (issuedAt.size() >= maxOutstanding) {
            skipped++;
            return false;
        }
        issuedAt.addLast(nowMs);
        // Advance from the previous due time so the average rate holds; floor at
        // (now + interval/2) so a late capture or a long stall never causes a catch-up burst.
        nextDueMs = Math.max(nextDueMs + intervalMs, nowMs + intervalMs / 2);
        return true;
    }

    /** The oldest outstanding capture delivered (or failed); returns its latency in ms, or -1. */
    long onDelivered(long nowMs) {
        Long t = issuedAt.pollFirst();
        return t == null ? -1 : nowMs - t;
    }

    boolean isOutstanding() {
        return !issuedAt.isEmpty();
    }

    /** Delay until the next tick worth checking. */
    long delayUntilNextCheckMs(long nowMs) {
        if (issuedAt.size() >= maxOutstanding) {
            // Delivery re-checks immediately; this only guards the stuck-capture case.
            return Math.max(1, Math.min(intervalMs, issuedAt.peekFirst() + stuckAfterMs - nowMs));
        }
        return Math.max(1, nextDueMs - nowMs);
    }

    /** Ticks skipped because the in-flight limit was reached, since the last call. */
    int takeSkipped() {
        int s = skipped;
        skipped = 0;
        return s;
    }
}
