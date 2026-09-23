package com.mentra.asg_client.camera.preview;

/**
 * Turns raw encoder output into access units. MediaCodec emits SPS/PPS once, as a codec-config
 * buffer, and never repeats them inline (the 2026-09-23 spike's 5 s dump has one SPS/PPS and five
 * IDRs). The builder keeps the config and sends it as a config unit before every keyframe, so the
 * phone can start decoding at any keyframe. Frames before the first keyframe are dropped.
 * Not thread-safe: call from the encoder's callback thread only.
 */
public class AccessUnitBuilder {

    public interface Sink {
        void onUnit(AccessUnit unit);
    }

    private final Sink sink;
    private byte[] config;
    private boolean sawKeyframe;

    public AccessUnitBuilder(Sink sink) {
        this.sink = sink;
    }

    public void accept(byte[] data, long ptsUs, boolean isConfig, boolean isKeyframe) {
        if (isConfig) {
            config = data;
            return;
        }
        if (!sawKeyframe) {
            if (!isKeyframe || config == null) {
                return;
            }
            sawKeyframe = true;
        }
        if (isKeyframe) {
            sink.onUnit(new AccessUnit(config, ptsUs, AccessUnit.FLAG_CONFIG));
        }
        sink.onUnit(new AccessUnit(data, ptsUs, isKeyframe ? AccessUnit.FLAG_KEYFRAME : 0));
    }
}
