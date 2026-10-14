package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.InvitationAcceptance

/**
 * `JourneyActionExecutor.acceptInvitation` refused to bind an invitation, before writing anything.
 * `JourneyService` ends the journey as it ends any `Transition.Abort`.
 */
class InvitationRefusedException(val refusal: InvitationAcceptance.Refusal) : RuntimeException(refusal.name)
