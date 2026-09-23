package com.mentra.asg_client.camera.preview;

/** Camera preview format (CONTEXT.md, ADR 0013): which transport the preview runs. One at a time. */
public enum PreviewFormat {
    JPEG("jpeg"),
    H264("h264");

    /** Value of the {@code format} start param and of {@code format} in the started status. */
    public final String wire;

    PreviewFormat(String wire) {
        this.wire = wire;
    }

    /** Absent or empty means JPEG (older phones never send it); an unknown value returns null. */
    public static PreviewFormat fromWire(String value) {
        if (value == null || value.isEmpty()) {
            return JPEG;
        }
        for (PreviewFormat format : values()) {
            if (format.wire.equals(value)) {
                return format;
            }
        }
        return null;
    }
}
