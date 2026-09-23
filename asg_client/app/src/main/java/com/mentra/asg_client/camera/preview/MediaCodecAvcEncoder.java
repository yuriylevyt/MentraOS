package com.mentra.asg_client.camera.preview;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Hardware AVC encoder with a Surface input (ADR 0013). The camera draws into the Surface that
 * {@link #start} returns; encoded buffers come back through {@link Output} on the given handler.
 */
public class MediaCodecAvcEncoder {
    private static final String TAG = "CameraPreviewH264";

    /** The MT6761 hardware encoder the 2026-09-23 spike measured (Surface input, High profile). */
    static final String PREFERRED_CODEC = "OMX.MTK.VIDEO.ENCODER.AVC";

    public interface Output {
        void onEncoded(byte[] data, long ptsUs, boolean isConfig, boolean isKeyframe);

        void onError(String reason);
    }

    private MediaCodec codec;

    /**
     * CBR at {@code bitrateKbps}: the MTK encoder advertises VBR only, but the spike's CBR request
     * was accepted and held 1.46–1.50 Mbps. {@code maxFpsToEncoder} caps what reaches the encoder
     * when the sensor runs faster than the frame interval asks (API 29+; ignored below).
     */
    static MediaFormat buildFormat(int width, int height, int bitrateKbps, int frameRate,
            long keyframeIntervalMs, int maxFpsToEncoder) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalMs / 1000f);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
        if (maxFpsToEncoder > 0 && maxFpsToEncoder < frameRate
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, maxFpsToEncoder);
        }
        return format;
    }

    /** Configures and starts the codec; returns the input Surface for the camera. */
    public Surface start(MediaFormat format, Handler handler, Output output) throws IOException {
        codec = createCodec();
        codec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec c, int index) {
                // Surface input: the camera fills the codec directly.
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec c, int index, @NonNull MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer buffer = c.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(data);
                        output.onEncoded(
                                data,
                                info.presentationTimeUs,
                                (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0,
                                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    }
                    c.releaseOutputBuffer(index, false);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "encoder output after release", e);
                }
            }

            @Override
            public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "encoder error", e);
                output.onError("encoder_error");
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat f) {
                Log.i(TAG, "encoder output format " + f);
            }
        }, handler);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = codec.createInputSurface();
        codec.start();
        return surface;
    }

    public String codecName() {
        return codec == null ? "none" : codec.getName();
    }

    /** Keyframe request (CONTEXT.md): the encoder emits a sync frame as soon as it can. */
    public void requestKeyframe() {
        if (codec == null) {
            return;
        }
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
            codec.setParameters(params);
            Log.i(TAG, "keyframe requested");
        } catch (IllegalStateException e) {
            Log.w(TAG, "keyframe request after release", e);
        }
    }

    /** Idempotent. Call only after the camera stopped drawing into the Surface. */
    public void release() {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "codec.stop() threw", e);
        }
        codec.release();
        codec = null;
    }

    private static MediaCodec createCodec() throws IOException {
        try {
            return MediaCodec.createByCodecName(PREFERRED_CODEC);
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, PREFERRED_CODEC + " unavailable, using the default AVC encoder", e);
            return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        }
    }
}
