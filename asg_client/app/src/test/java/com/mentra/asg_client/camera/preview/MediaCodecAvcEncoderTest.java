package com.mentra.asg_client.camera.preview;

import static org.assertj.core.api.Assertions.assertThat;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MediaCodecAvcEncoderTest {

    @Test
    public void buildFormat_requestsSurfaceInputCbrAndTheKeyframeInterval() {
        MediaFormat f = MediaCodecAvcEncoder.buildFormat(1280, 720, 1500, 15, 1000L, 10);

        assertThat(f.getString(MediaFormat.KEY_MIME)).isEqualTo(MediaFormat.MIMETYPE_VIDEO_AVC);
        assertThat(f.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(1280);
        assertThat(f.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(720);
        assertThat(f.getInteger(MediaFormat.KEY_COLOR_FORMAT))
                .isEqualTo(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        assertThat(f.getInteger(MediaFormat.KEY_BIT_RATE)).isEqualTo(1_500_000);
        assertThat(f.getInteger(MediaFormat.KEY_BITRATE_MODE))
                .isEqualTo(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        assertThat(f.getFloat(MediaFormat.KEY_I_FRAME_INTERVAL)).isEqualTo(1.0f);
        assertThat(f.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER)).isEqualTo(10f);
    }

    @Test
    public void buildFormat_givesTheEncoderTheFrameRateItActuallyReceives() {
        // Sensor at 15 fps, capped to 10 fps before the encoder: the encoder turns the keyframe
        // interval into a frame count from KEY_FRAME_RATE, so 15 here meant a keyframe every 1.5 s.
        MediaFormat f = MediaCodecAvcEncoder.buildFormat(1280, 720, 1500, 15, 1000L, 10);

        assertThat(f.getInteger(MediaFormat.KEY_FRAME_RATE)).isEqualTo(10);
    }

    @Test
    public void encoderFrameRate_isTheSlowerOfTheSensorAndTheCap() {
        assertThat(MediaCodecAvcEncoder.encoderFrameRate(15, 10)).isEqualTo(10);
        assertThat(MediaCodecAvcEncoder.encoderFrameRate(15, 5)).isEqualTo(5);
        assertThat(MediaCodecAvcEncoder.encoderFrameRate(15, 15)).isEqualTo(15);
        assertThat(MediaCodecAvcEncoder.encoderFrameRate(10, 30)).isEqualTo(10);
        assertThat(MediaCodecAvcEncoder.encoderFrameRate(15, 0)).isEqualTo(15);
    }

    @Test
    public void buildFormat_leavesMaxFpsUnsetWhenTheSensorRateIsWanted() {
        MediaFormat f = MediaCodecAvcEncoder.buildFormat(1280, 720, 1500, 15, 500L, 15);

        assertThat(f.containsKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER)).isFalse();
        assertThat(f.getFloat(MediaFormat.KEY_I_FRAME_INTERVAL)).isEqualTo(0.5f);
    }
}
