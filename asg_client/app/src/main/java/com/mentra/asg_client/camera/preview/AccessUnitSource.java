package com.mentra.asg_client.camera.preview;

/** Produces H.264 access units from the camera. The first unit is always a config unit. */
public interface AccessUnitSource {

    interface UnitListener {
        void onUnit(AccessUnit unit);

        void onError(String reason);
    }

    void start(PreviewConfig config, UnitListener listener) throws Exception;

    /** Keyframe request (CONTEXT.md): ask the encoder for a sync frame as soon as it can. */
    void requestKeyframe();

    /** Releases the camera and encoder. Idempotent; blocks (bounded) until the camera has closed. */
    void stop();
}
