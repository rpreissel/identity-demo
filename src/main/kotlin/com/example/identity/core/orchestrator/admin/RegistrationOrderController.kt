package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag
import com.example.identity.core.orchestrator.session.FeatureFlagService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RegistrationOrderState(val enrollFirst: Boolean)

/**
 * Runtime toggle for REGISTER's "Enrollment zuerst" order (`RegisterDispatchStrategy`): applies to
 * every new REGISTER journey. A named endpoint rather than a generic flag API: the meaning is the
 * contract, the feature flag only its storage.
 */
@RestController
@RequestMapping("$ADMIN_API/registration-order")
@Tag(name = "Admin: registration order", description = "Toggle between ident-first and enroll-first REGISTER")
class RegistrationOrderController(private val featureFlagService: FeatureFlagService) {

    @GetMapping
    @Operation(summary = "Current REGISTER order")
    fun get(): RegistrationOrderState = RegistrationOrderState(featureFlagService.isEnabled(JourneyFeatureFlag.REGISTER_ENROLL_FIRST.key))

    @PutMapping
    @Operation(summary = "Set REGISTER order", description = "Takes effect for the next brand-new REGISTER journey - a running one keeps whichever order it started with.")
    fun put(@RequestBody request: RegistrationOrderState) {
        featureFlagService.setEnabled(JourneyFeatureFlag.REGISTER_ENROLL_FIRST.key, request.enrollFirst)
    }
}
