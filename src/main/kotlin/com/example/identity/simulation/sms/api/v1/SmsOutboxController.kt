package com.example.identity.simulation.sms.api.v1

import com.example.identity.demo.demo_mode.DemoSurface
import com.example.identity.simulation.sms.SentSms
import com.example.identity.simulation.sms.SmsGateway
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The provider's sent messages, standing in for the recipient's phone: where the tester reads a
 * TAN the app does not show (page `/briefkasten/`, docs/10-frontend.md). Only in demo mode
 * (ADR-36).
 */
@RestController
@DemoSurface
@RequestMapping("/mock-sms")
@Tag(name = "Mock SMS", description = "Simulierter SMS-Anbieter - kein Endpunkt dieser Anwendung, sondern der Fremddienst")
class SmsOutboxController(private val gateway: SmsGateway) {

    @GetMapping("outbox")
    @Operation(summary = "Postausgang", description = "Die zuletzt verschickten SMS, neueste zuerst.")
    fun outbox(): List<SentSms> = gateway.outbox()
}
