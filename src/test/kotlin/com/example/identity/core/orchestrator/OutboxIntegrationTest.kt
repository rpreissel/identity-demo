package com.example.identity.core.orchestrator

import io.kotest.matchers.shouldBe

/**
 * The outboxes of the simulated SMS provider and mail server, read by the Briefkasten on the
 * person register's page (docs/10-frontend.md): what was sent is there over HTTP, newest first.
 */
class OutboxIntegrationTest : IntegrationTestSupport() {

    @Suppress("UNCHECKED_CAST")
    private fun newestIn(outbox: String): Map<String, Any?> =
        (restTemplate.getForObject("http://localhost:$port$outbox", List::class.java)!! as List<Map<String, Any?>>).first()

    init {
        given("an SMS and an e-mail just sent") {
            `when`("the Briefkasten reads both outboxes") {
                smsGateway.sendTan("+491700000099", "424242")
                mailServer.sendCode("outbox@example.org", "737373")

                val newestSms = newestIn("/mock-sms/outbox")
                val newestMail = newestIn("/mock-mail/outbox")

                then("/mock-sms/outbox lists the SMS first") {
                    newestSms["phoneNumber"] shouldBe "+491700000099"
                    newestSms["tan"] shouldBe "424242"
                }

                then("/mock-mail/outbox lists the e-mail first") {
                    newestMail["address"] shouldBe "outbox@example.org"
                    newestMail["code"] shouldBe "737373"
                }
            }
        }
    }
}
