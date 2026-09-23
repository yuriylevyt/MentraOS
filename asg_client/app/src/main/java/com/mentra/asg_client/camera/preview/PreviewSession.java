package com.mentra.asg_client.camera.preview;

/**
 * One running camera preview, whatever its format. {@link CameraPreviewSession} runs JPEG;
 * {@code H264PreviewSession} runs H.264. The command handler picks one per start by format.
 */
public interface PreviewSession {
    void start(PreviewConfig config);

    void stop(String reason);

    boolean isActive();

    /** Pumps the periodic max-duration, failure and stats checks; safe to call from a timer. */
    void tick();
}
