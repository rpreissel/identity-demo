package com.example.identity.core.orchestrator.kc

import com.example.identity.core.orchestrator.IntegrationTestSupport
import com.example.identity.simulation.personenverzeichnis.Einladungen
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Keycloak's second federation reads invitations here (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md):
 * by id only, anchored on that id, with the person's master data and the two markers; an ended
 * invitation comes back disabled.
 */
class KcInvitationLookupIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @Autowired
    private lateinit var einladungen: Einladungen

    @Autowired
    private lateinit var clock: Clock

    private fun anchor(value: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns
            PeerAuthAssertion(jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelAnchor = value, subject = null)
    }

    private fun lookup(id: String) =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/invitations/$id", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("Authorization", "Bearer peer-auth") }), mapType
        )

    private fun status(id: String): HttpStatus =
        try {
            HttpStatus.valueOf(lookup(id).statusCode.value())
        } catch (e: HttpClientErrorException) {
            HttpStatus.valueOf(e.statusCode.value())
        }

    private fun issueForMax(): String =
        checkNotNull(einladungen.ausstellen("P000000001", "beitragsrueckerstattung", "loa1", clock.instant().plus(Duration.ofDays(7)))?.einladungId)

    init {
        Given("an open invitation") {
            When("Keycloak looks it up by its id") {
                Then("it reads a user of its own: the person's data, both markers, no account, enabled") {
                    val id = issueForMax()
                    anchor(id)

                    val view = lookup(id).body!!

                    view["invitation"] shouldBe id
                    view["username"] shouldBe "invitation-$id"
                    view["enabled"] shouldBe true
                    view["firstName"] shouldBe "Max"
                    @Suppress("UNCHECKED_CAST")
                    val attributes = view["attributes"] as Map<String, String>
                    attributes shouldContainAll mapOf(
                        "orchestratorInvitation" to id,
                        "orchestratorProcess" to "beitragsrueckerstattung",
                        "personId" to "P000000001",
                        "kvnr" to "A123456789",
                    )
                    attributes shouldNotContainKey "orchestratorAccountId"
                }
            }
        }

        Given("an invitation the business system completed") {
            When("Keycloak looks it up") {
                Then("it comes back disabled, so Keycloak issues no further token") {
                    val id = issueForMax()
                    einladungen.abschliessen(id)
                    anchor(id)

                    lookup(id).body!!["enabled"] shouldBe false
                }
            }
        }

        Given("an id the register does not know") {
            When("Keycloak looks it up") {
                Then("the answer is 404") {
                    anchor("0".repeat(64))

                    status("0".repeat(64)) shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        Given("an open invitation and an assertion anchored on another one") {
            When("Keycloak looks it up") {
                Then("the lookup is refused") {
                    val id = issueForMax()
                    anchor("another-invitation")

                    status(id).is4xxClientError shouldBe true
                }
            }
        }
    }
}
