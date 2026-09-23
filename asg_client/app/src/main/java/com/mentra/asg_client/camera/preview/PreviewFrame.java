package com.mentra.asg_client.camera.preview;

/** A single captured, JPEG-encoded camera preview frame. */
public class PreviewFrame {
    public final byte[] jpegBytes;
    public final long frameId;
    public final long captureTimeMs;

    public PreviewFrame(byte[] jpegBytes, long frameId, long captureTimeMs) {
        this.jpegBytes = jpegBytes;
        this.frameId = frameId;
        this.captureTimeMs = captureTimeMs;
    }
}
