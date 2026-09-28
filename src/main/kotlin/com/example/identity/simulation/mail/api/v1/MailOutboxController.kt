package com.example.identity.simulation.mail.api.v1

import com.example.identity.demo.demo_mode.DemoSurface
import com.example.identity.simulation.mail.MailServer
import com.example.identity.simulation.mail.SentMail
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The server's sent mails, standing in for the recipient's inbox: where the tester reads a code
 * the app does not show (page `/briefkasten/`, docs/10-frontend.md). Only in demo mode (ADR-36).
 */
@RestController
@DemoSurface
@RequestMapping("/mock-mail")
@Tag(name = "Mock Mail", description = "Simulierter Mailserver - kein Endpunkt dieser Anwendung, sondern der Fremddienst")
class MailOutboxController(private val server: MailServer) {

    @GetMapping("outbox")
    @Operation(summary = "Postausgang", description = "Die zuletzt verschickten E-Mails, neueste zuerst.")
    fun outbox(): List<SentMail> = server.outbox()
}
