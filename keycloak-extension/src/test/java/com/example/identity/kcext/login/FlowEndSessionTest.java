package com.example.identity.kcext.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure logic: which session the end-of-flow report may name (ADR-59). */
class FlowEndSessionTest {

    @Test
    void theSessionOfTheRunsOwnUserIsReported() {
        assertTrue(OrchestratorNotes.sessionBelongsToRun("user-a", "user-a"));
    }

    @Test
    void aSessionAnotherTabOpenedForAnotherUserIsNotReported() {
        assertFalse(OrchestratorNotes.sessionBelongsToRun("user-a", "user-b"));
    }

    @Test
    void aSessionIsNotReportedForARunWithoutUser() {
        assertFalse(OrchestratorNotes.sessionBelongsToRun("user-a", null));
    }

    @Test
    void withoutSessionTheRunsNewSessionIsReported() {
        assertTrue(OrchestratorNotes.sessionBelongsToRun(null, "user-b"));
    }
}
