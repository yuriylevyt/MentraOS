package com.mentra.asg_client.camera.preview;

/**
 * One access unit on the H.264 preview stream (CONTEXT.md): a frame's Annex-B payload, or a config
 * unit carrying SPS/PPS. Flag values are the wire values (ADR 0013).
 */
public final class AccessUnit {
    public static final int FLAG_KEYFRAME = 0x01;
    public static final int FLAG_CONFIG = 0x02;

    public final byte[] payload;
    /** Encoder presentation time in microseconds. */
    public final long ptsUs;
    public final int flags;

    public AccessUnit(byte[] payload, long ptsUs, int flags) {
        this.payload = payload;
        this.ptsUs = ptsUs;
        this.flags = flags;
    }

    public boolean isKeyframe() {
        return (flags & FLAG_KEYFRAME) != 0;
    }

    public boolean isConfig() {
        return (flags & FLAG_CONFIG) != 0;
    }
}
