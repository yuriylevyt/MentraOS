package com.mentra.asg_client.camera.preview;

/** Transport for pushing preview frames somewhere (e.g. an HTTP receiver on the phone). */
public interface PreviewFrameSink {

    /** Async completion callback for {@link #trySend}. */
    interface Callback {
        void onResult(boolean success);
    }

    /**
     * Non-blocking. Returns {@code false} immediately, without sending, if a previous send is
     * still in flight. On acceptance, completion arrives later via {@code callback.onResult}.
     */
    boolean trySend(PreviewFrame frame, Callback callback);

    void configure(String url, String token);

    void close();
}
