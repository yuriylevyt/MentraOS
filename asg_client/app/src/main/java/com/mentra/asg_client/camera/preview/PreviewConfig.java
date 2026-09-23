package com.mentra.asg_client.camera.preview;

/** Already-clamped configuration for a preview session; no clamping happens downstream. */
public class PreviewConfig {
    public final int width;
    public final int height;
    public final int quality;
    public final long intervalMs;
    public final String url;
    public final String token;
    public final PreviewFormat format;
    /** H.264 only: target encoder bitrate. */
    public final int bitrateKbps;
    /** H.264 only: time between keyframes. */
    public final long keyframeIntervalMs;

    /** A JPEG preview config (the format before ADR 0013). */
    public PreviewConfig(int width, int height, int quality, long intervalMs, String url, String token) {
        this(width, height, quality, intervalMs, url, token, PreviewFormat.JPEG, 0, 0L);
    }

    public PreviewConfig(
            int width,
            int height,
            int quality,
            long intervalMs,
            String url,
            String token,
            PreviewFormat format,
            int bitrateKbps,
            long keyframeIntervalMs) {
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.intervalMs = intervalMs;
        this.url = url;
        this.token = token;
        this.format = format;
        this.bitrateKbps = bitrateKbps;
        this.keyframeIntervalMs = keyframeIntervalMs;
    }
}
