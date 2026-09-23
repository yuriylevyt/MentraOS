package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class AccessUnitBuilderTest {

    private static final byte[] SPS_PPS = {0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68};
    private static final byte[] IDR = {0, 0, 0, 1, 0x65, 1};
    private static final byte[] P = {0, 0, 0, 1, 0x41, 2};

    private final List<AccessUnit> units = new ArrayList<>();
    private AccessUnitBuilder builder;

    @Before
    public void setUp() {
        builder = new AccessUnitBuilder(units::add);
    }

    @Test
    public void configAloneEmitsNothing() {
        builder.accept(SPS_PPS, 0L, true, false);
        assertTrue(units.isEmpty());
    }

    @Test
    public void keyframeIsPrecededByTheConfigWithTheSameTimestamp() {
        builder.accept(SPS_PPS, 0L, true, false);
        builder.accept(IDR, 66_000L, false, true);
        builder.accept(P, 133_000L, false, false);

        assertEquals(3, units.size());
        assertEquals(AccessUnit.FLAG_CONFIG, units.get(0).flags);
        assertArrayEquals(SPS_PPS, units.get(0).payload);
        assertEquals(66_000L, units.get(0).ptsUs);
        assertEquals(AccessUnit.FLAG_KEYFRAME, units.get(1).flags);
        assertArrayEquals(IDR, units.get(1).payload);
        assertEquals(66_000L, units.get(1).ptsUs);
        assertEquals(0, units.get(2).flags);
        assertEquals(133_000L, units.get(2).ptsUs);
    }

    @Test
    public void framesBeforeTheFirstKeyframeOrWithoutConfigAreDropped() {
        builder.accept(IDR, 1L, false, true); // no config yet
        builder.accept(SPS_PPS, 2L, true, false);
        builder.accept(P, 3L, false, false); // no keyframe yet
        assertTrue(units.isEmpty());

        builder.accept(IDR, 4L, false, true);
        assertEquals(2, units.size());
    }

    @Test
    public void everyLaterKeyframeGetsTheConfigAgain() {
        builder.accept(SPS_PPS, 0L, true, false);
        builder.accept(IDR, 1L, false, true);
        builder.accept(P, 2L, false, false);
        builder.accept(IDR, 3L, false, true);

        assertEquals(5, units.size());
        assertTrue(units.get(3).isConfig());
        assertTrue(units.get(4).isKeyframe());
    }

    @Test
    public void aNewConfigReplacesTheCachedOne() {
        byte[] newConfig = {0, 0, 0, 1, 0x67, 9, 0, 0, 0, 1, 0x68};
        builder.accept(SPS_PPS, 0L, true, false);
        builder.accept(IDR, 1L, false, true);
        builder.accept(newConfig, 2L, true, false);
        builder.accept(IDR, 3L, false, true);

        assertArrayEquals(newConfig, units.get(2).payload);
    }
}
