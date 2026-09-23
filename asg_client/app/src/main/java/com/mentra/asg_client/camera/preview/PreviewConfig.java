package com.mentra.asg_client.camera.preview;

/** Already-clamped configuration for a preview session; no clamping happens downstream. */
public class PreviewConfig {
    public final int width;
    public final int height;
    public final int quality;
    public final long intervalMs;
    public final String url;
    public final String token;

    public PreviewConfig(int width, int height, int quality, long intervalMs, String url, String token) {
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.intervalMs = intervalMs;
        this.url = url;
        this.token = token;
    }
}
