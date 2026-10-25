package org.mule.extension.agent.composer.internal.model;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PendingToolPermissionStateTest {

    @Test
    public void expirationSurvivesCheckpointRoundTrip() {
        PendingToolPermissionState state = new PendingToolPermissionState();
        state.setCreatedAtEpochMs(1_000L);
        state.setExpiresAtEpochMs(2_000L);

        PendingToolPermissionState restored = new Gson().fromJson(
                new Gson().toJson(state), PendingToolPermissionState.class);

        assertFalse(restored.isExpired(1_999L));
        assertTrue(restored.isExpired(2_000L));
    }
}
