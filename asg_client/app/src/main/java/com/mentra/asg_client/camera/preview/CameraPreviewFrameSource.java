package com.mentra.asg_client.camera.preview;

/**
 * Produces JPEG preview frames from the camera, one encode per {@code intervalMs} at most. The
 * source owns the capture pacing so the chip never JPEG-encodes a frame nobody asked for.
 */
public interface CameraPreviewFrameSource {

    interface FrameListener {
        void onFrame(byte[] jpegBytes, long captureTimeMs);

        void onError(String reason);
    }

    /**
     * @param intervalMs capture one JPEG roughly every {@code intervalMs}; a tick is skipped while
     *                   the previous capture has not delivered its image yet.
     */
    void start(int width, int height, int quality, long intervalMs, FrameListener listener) throws Exception;

    /**
     * Releases the camera. Idempotent and callable from any thread; when called off the source's
     * own thread it blocks (bounded) until the camera device has actually closed.
     */
    void stop();
}
