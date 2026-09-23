package com.mentra.asg_client.receiver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntentCommandReceiverTest {

    @Test
    public void cameraPreviewStartIsRejectedInReleaseBuilds() {
        assertFalse(IntentCommandReceiver.isAllowedViaIntent("start_camera_preview", false));
    }

    @Test
    public void cameraPreviewStartIsAllowedInDebugBuilds() {
        assertTrue(IntentCommandReceiver.isAllowedViaIntent("start_camera_preview", true));
    }

    @Test
    public void otherCommandsKeepTheirExistingBehavior() {
        assertTrue(IntentCommandReceiver.isAllowedViaIntent("stop_camera_preview", false));
        assertTrue(IntentCommandReceiver.isAllowedViaIntent("take_photo", false));
        assertTrue(IntentCommandReceiver.isAllowedViaIntent("ping", false));
    }
}
