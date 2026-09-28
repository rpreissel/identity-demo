package com.example.identity.core.orchestrator

import io.kotest.matchers.shouldBe

/**
 * The outboxes of the simulated SMS provider and mail server, read by the Briefkasten on the
 * person register's page (docs/10-frontend.md): what was sent is there over HTTP, newest first.
 */
class OutboxIntegrationTest : IntegrationTestSupport() {

    init {
        given("an SMS and an e-mail just sent") {
            smsGateway.sendTan("+491700000099", "424242")
            mailServer.sendCode("outbox@example.org", "737373")

            then("/mock-sms/outbox lists the SMS first") {
                @Suppress("UNCHECKED_CAST")
                val newest = (restTemplate.getForObject("http://localhost:$port/mock-sms/outbox", List::class.java)!! as List<Map<String, Any?>>).first()
                newest["phoneNumber"] shouldBe "+491700000099"
                newest["tan"] shouldBe "424242"
            }

            then("/mock-mail/outbox lists the e-mail first") {
                @Suppress("UNCHECKED_CAST")
                val newest = (restTemplate.getForObject("http://localhost:$port/mock-mail/outbox", List::class.java)!! as List<Map<String, Any?>>).first()
                newest["address"] shouldBe "outbox@example.org"
                newest["code"] shouldBe "737373"
            }
        }
    }
}
