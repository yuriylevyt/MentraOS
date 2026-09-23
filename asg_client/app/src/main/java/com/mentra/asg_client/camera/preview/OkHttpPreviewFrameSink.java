package com.mentra.asg_client.camera.preview;

import androidx.annotation.NonNull;

import com.mentra.asg_client.AsgConstants;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Posts each preview frame as a raw {@code image/jpeg} body (not multipart) over one long-lived,
 * keep-alive {@link OkHttpClient} — deliberately not the per-call-client, multipart pattern in
 * {@code io/media/upload/MediaUploadService.java}.
 */
public class OkHttpPreviewFrameSink implements PreviewFrameSink {

    private static final MediaType JPEG = MediaType.parse("image/jpeg");

    private final OkHttpClient client;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<Call> currentCall = new AtomicReference<>();
    private volatile String url;
    private volatile String token;

    public OkHttpPreviewFrameSink() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(AsgConstants.CAMERA_PREVIEW_HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(AsgConstants.CAMERA_PREVIEW_HTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(AsgConstants.CAMERA_PREVIEW_HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(AsgConstants.CAMERA_PREVIEW_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void configure(String url, String token) {
        this.url = url;
        this.token = token;
    }

    @Override
    public boolean trySend(PreviewFrame frame, PreviewFrameSink.Callback callback) {
        if (!inFlight.compareAndSet(false, true)) {
            return false;
        }

        RequestBody body = RequestBody.create(JPEG, frame.jpegBytes);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("X-Frame-Id", Long.toString(frame.frameId))
                .header("X-Capture-Time-Ms", Long.toString(frame.captureTimeMs))
                .post(body)
                .build();

        Call call = client.newCall(request);
        currentCall.set(call);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                boolean success = response.isSuccessful();
                response.close();
                inFlight.set(false);
                callback.onResult(success);
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                inFlight.set(false);
                callback.onResult(false);
            }
        });
        return true;
    }

    @Override
    public void close() {
        Call call = currentCall.get();
        if (call != null) {
            call.cancel();
        }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
