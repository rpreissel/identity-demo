package com.example.identity.kcext.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tool and method ids come from form data and become part of a signed address. A value that would
 * move the address is refused before anything is signed.
 */
class OrchestratorClientSegmentTest {

    @Test
    void toolIdsAndIdsPassUnchanged() {
        assertEquals("auth-sms", OrchestratorClient.segment("auth-sms"));
        assertEquals("3f1c2a9e-7d4b-4c1a-9a55-0f8e6b2d1c3a", OrchestratorClient.segment("3f1c2a9e-7d4b-4c1a-9a55-0f8e6b2d1c3a"));
        assertEquals("42", OrchestratorClient.segment("42"));
    }

    @Test
    void aValueThatWouldMoveTheAddressIsRefused() {
        for (String moving : new String[]{"..", ".", "auth-sms/../../admin", "auth-sms?x=1", "auth-sms#f", "a%2Fb", "", "a b"}) {
            assertThrows(IllegalArgumentException.class, () -> OrchestratorClient.segment(moving), moving);
        }
        assertThrows(IllegalArgumentException.class, () -> OrchestratorClient.segment(null));
    }
}
