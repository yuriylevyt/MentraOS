package com.mentra.asg_client.camera.preview;

/** One connection carrying the H.264 preview stream to the phone. Single use: start once, close once. */
public interface StreamTransport {

    /** Called on the transport's writer thread (and the offer() caller for drops). */
    interface Events {
        /** The phone answered 200; access units flow from here on. */
        void onConnected();

        /** One access unit was fully written to the socket. */
        void onDelivered(AccessUnit unit);

        /** Access units dropped because the connection backed up or a unit was oversized. */
        void onDropped(int units);

        /** The queue was dropped; the stream resumes at the next keyframe, so ask for one now. */
        void onKeyframeNeeded();

        /** The phone answered 401: the token is wrong. The transport is dead. */
        void onUnauthorized();

        /** Connect, response or write failed. The transport is dead. */
        void onFailure(String reason);
    }

    /** Connects and starts writing on a background thread. */
    void start();

    /** Non-blocking; queues the unit, or drops it under backpressure. */
    void offer(AccessUnit unit);

    /** Idempotent; closes the socket, which also unblocks a stuck write. */
    void close();
}
