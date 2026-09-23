package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreviewFormatTest {

    @Test
    public void fromWire_absentOrEmptyIsJpeg_knownValuesMap_unknownIsNull() {
        assertEquals(PreviewFormat.JPEG, PreviewFormat.fromWire(null));
        assertEquals(PreviewFormat.JPEG, PreviewFormat.fromWire(""));
        assertEquals(PreviewFormat.JPEG, PreviewFormat.fromWire("jpeg"));
        assertEquals(PreviewFormat.H264, PreviewFormat.fromWire("h264"));
        assertNull(PreviewFormat.fromWire("H264"));
        assertNull(PreviewFormat.fromWire("vp8"));
    }

    @Test
    public void legacyConfigConstructor_isAJpegConfig() {
        PreviewConfig config = new PreviewConfig(1280, 720, 60, 100L, "http://h:1/preview", "tok");
        assertEquals(PreviewFormat.JPEG, config.format);
        assertEquals(0, config.bitrateKbps);
        assertEquals(0L, config.keyframeIntervalMs);
    }

    @Test
    public void accessUnitFlags_matchTheWireBits() {
        AccessUnit key = new AccessUnit(new byte[] {1}, 5L, AccessUnit.FLAG_KEYFRAME);
        AccessUnit config = new AccessUnit(new byte[] {2}, 5L, AccessUnit.FLAG_CONFIG);
        assertEquals(1, AccessUnit.FLAG_KEYFRAME);
        assertEquals(2, AccessUnit.FLAG_CONFIG);
        assertTrue(key.isKeyframe());
        assertFalse(key.isConfig());
        assertTrue(config.isConfig());
        assertFalse(config.isKeyframe());
    }
}
