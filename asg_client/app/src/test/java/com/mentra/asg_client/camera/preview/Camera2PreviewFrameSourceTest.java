package com.mentra.asg_client.camera.preview;

import static org.assertj.core.api.Assertions.assertThat;

import android.util.Range;
import android.util.Size;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for the pure helpers of {@link Camera2PreviewFrameSource}; fps cases use the real MT6761 AE
 * target-fps ranges reported by dumpsys: [15,15], [20,20], [5,30], [30,30]. Runs under Robolectric
 * (matching the rest of this package's tests) because plain android.jar stubs
 * {@code Range.getLower()}/{@code getUpper()} to throw.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class Camera2PreviewFrameSourceTest {

    @SuppressWarnings("unchecked")
    private static final Range<Integer>[] MT6761_RANGES =
            new Range[] {
                new Range<>(15, 15), new Range<>(20, 20), new Range<>(5, 30), new Range<>(30, 30)
            };

    @Test
    public void target10_picksFixed15() {
        assertThat(Camera2PreviewFrameSource.chooseFpsRange(MT6761_RANGES, 10))
                .isEqualTo(new Range<>(15, 15));
    }

    @Test
    public void target20_picksFixed20() {
        assertThat(Camera2PreviewFrameSource.chooseFpsRange(MT6761_RANGES, 20))
                .isEqualTo(new Range<>(20, 20));
    }

    @Test
    public void target25_picksFixed30() {
        assertThat(Camera2PreviewFrameSource.chooseFpsRange(MT6761_RANGES, 25))
                .isEqualTo(new Range<>(30, 30));
    }

    @Test
    public void target60_exceedsEveryUpperBound_picksHighestUpperBound() {
        assertThat(Camera2PreviewFrameSource.chooseFpsRange(MT6761_RANGES, 60))
                .isEqualTo(new Range<>(30, 30));
    }

    @Test
    public void target1_picksLowestFixedRange() {
        // No fixed range has x < 15, so the smallest fixed range >= 1 is [15,15]; the wide
        // [5,30] range would still encode up to 30fps, which is worse for a 1fps target.
        assertThat(Camera2PreviewFrameSource.chooseFpsRange(MT6761_RANGES, 1))
                .isEqualTo(new Range<>(15, 15));
    }

    @Test
    public void noFixedRangeAtOrAboveTarget_picksSmallestSufficientUpperBound() {
        @SuppressWarnings("unchecked")
        Range<Integer>[] ranges = new Range[] {new Range<>(5, 30), new Range<>(10, 60)};
        assertThat(Camera2PreviewFrameSource.chooseFpsRange(ranges, 20))
                .isEqualTo(new Range<>(5, 30));
    }

    @Test
    public void yuv_picksSmallestAtLeast320x240_realMt6761Sizes() {
        // Subset of the glasses' YUV_420_888 list: no small 16:9 size exists (192x108 is under
        // the minimum), so the 4:3 320x240 wins over 1280x720.
        Size[] sizes = {
            new Size(1920, 1080), new Size(1280, 720), new Size(640, 480), new Size(352, 288),
            new Size(320, 240), new Size(192, 144), new Size(192, 108), new Size(160, 96)
        };
        assertThat(Camera2PreviewFrameSource.chooseYuvSize(sizes)).isEqualTo(new Size(320, 240));
    }

    @Test
    public void yuv_nothingMeetsMinimum_usesLargest() {
        Size[] sizes = {new Size(176, 144), new Size(160, 120)};
        assertThat(Camera2PreviewFrameSource.chooseYuvSize(sizes)).isEqualTo(new Size(176, 144));
    }

    @Test
    public void yuv_noSizes_returnsNull() {
        assertThat(Camera2PreviewFrameSource.chooseYuvSize(null)).isNull();
    }

    @Test
    public void restrictedOpen_retriesUntilMaxAttemptsThenGivesUp() {
        int max = Camera2PreviewFrameSource.MAX_OPEN_ATTEMPTS;
        assertThat(Camera2PreviewFrameSource.shouldRetryRestrictedOpen(1, max)).isTrue();
        assertThat(Camera2PreviewFrameSource.shouldRetryRestrictedOpen(max - 1, max)).isTrue();
        assertThat(Camera2PreviewFrameSource.shouldRetryRestrictedOpen(max, max)).isFalse();
    }
}
