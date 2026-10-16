package com.example.identity.core.orchestrator.domain

import com.example.identity.core.orchestrator.domain.journey.StartRefusal
import com.example.identity.core.orchestrator.domain.journey.startRefusal
import com.example.identity.core.orchestrator.domain.journey.strategy.ConfirmPeerLoginJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.DeleteAccountJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.FastAccessJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.LookupLoginJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.ManageAuthMethodsJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.RegisterJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.StepUpJourney
import com.example.identity.core.orchestrator.domain.journey.strategy.WebSelectMethodJourney
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Where each journey may start - no Spring, no database (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 * The expectations are the table in docs/04-orchestrierung.md Abschnitt 2.
 */
class JourneysTest : BehaviorSpec({

    val appEntries = setOf(FastAccessJourney, RegisterJourney, LookupLoginJourney, ConfirmPeerLoginJourney)
    val webEntries = setOf(WebSelectMethodJourney, RegisterJourney)
    val whenAuthenticated = setOf(StepUpJourney, ManageAuthMethodsJourney, ConfirmPeerLoginJourney, DeleteAccountJourney)

    given("every journey and channel type") {
        val expected = mapOf(ChannelType.APP to appEntries, ChannelType.WEB to webEntries)

        Journeys.all.forEach { journey ->
            ChannelType.entries.forEach { channel ->
                val opens = journey in expected.getValue(channel)
                `when`("a $channel channel is opened with ${journey.name.lowercase()}") {
                    val resolved = Journeys.fromRequest(journey.name.lowercase(), channel)

                    then(if (opens) "the journey is accepted" else "it is refused") {
                        resolved shouldBe if (opens) journey else null
                    }
                }
            }
        }
    }

    given("a channel opened without naming a journey") {
        `when`("the default applies") {
            then("the App starts FAST_ACCESS and Keycloak WEB_SELECT_METHOD") {
                Journeys.fromRequest(null, ChannelType.APP) shouldBe FastAccessJourney
                Journeys.fromRequest(null, ChannelType.WEB) shouldBe WebSelectMethodJourney
            }
        }
    }

    given("a journey name that does not exist") {
        `when`("a channel is opened with it") {
            then("it is refused on both channel types") {
                Journeys.fromRequest("nonsense", ChannelType.APP) shouldBe null
                Journeys.fromRequest("nonsense", ChannelType.WEB) shouldBe null
            }
        }
    }

    given("a stored journey name") {
        `when`("it is read back") {
            then("it names the same journey") {
                Journeys.all.forEach { Journeys.byName(it.name) shouldBe it }
            }
        }
    }

    given("a journey started on an already authenticated channel") {
        whenAuthenticated.forEach { journey ->
            `when`("${journey.name} is started on an ANONYMOUS channel") {
                then("it is refused for lack of a login") {
                    journey.startRefusal(ChannelState.ANONYMOUS, hasAccount = true) shouldBe StartRefusal.NOT_LOGGED_IN
                }
            }
            `when`("${journey.name} is started on an AUTHENTICATED channel with an account") {
                then("it may start") {
                    journey.startRefusal(ChannelState.AUTHENTICATED, hasAccount = true) shouldBe null
                }
            }
        }
    }

    given("a process access, signed in without an account (ADR-48)") {
        `when`("it starts a journey on its channel") {
            then("every one is refused: each acts on the account itself") {
                whenAuthenticated.forEach { it.startRefusal(ChannelState.AUTHENTICATED, hasAccount = false) shouldBe StartRefusal.NO_ACCOUNT }
            }
        }
    }

    given("a journey that is not started on an authenticated channel") {
        (Journeys.all - whenAuthenticated).forEach { journey ->
            `when`("${journey.name} is asked for") {
                then("it is refused as not startable there") {
                    journey.startRefusal(ChannelState.AUTHENTICATED, hasAccount = true) shouldBe StartRefusal.NOT_STARTABLE_WHEN_AUTHENTICATED
                }
            }
        }
    }
})
