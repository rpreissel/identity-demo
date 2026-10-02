package com.example.identity.core.orchestrator.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Where each intent may start - no Spring, no database (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 * The expectations are the table in docs/04-orchestrierung.md Abschnitt 2.
 */
class AuthIntentTest : BehaviorSpec({

    val appEntries = setOf(AuthIntent.FAST_ACCESS, AuthIntent.REGISTER, AuthIntent.LOOKUP_LOGIN, AuthIntent.CONFIRM_PEER_LOGIN)
    val webEntries = setOf(AuthIntent.WEB_SELECT_METHOD, AuthIntent.REGISTER)
    val inSession = setOf(
        AuthIntent.STEP_UP, AuthIntent.MANAGE_AUTH_METHODS, AuthIntent.CONFIRM_PEER_LOGIN,
        AuthIntent.DELETE_ACCOUNT, AuthIntent.LOGOUT
    )

    given("every intent and channel type") {
        val expected = mapOf(ChannelType.APP to appEntries, ChannelType.WEB to webEntries)

        AuthIntent.entries.forEach { intent ->
            ChannelType.entries.forEach { channel ->
                val opens = intent in expected.getValue(channel)
                `when`("a $channel channel is opened with ${intent.name.lowercase()}") {
                    val resolved = AuthIntent.fromRequest(intent.name.lowercase(), channel)

                    then(if (opens) "the intent is accepted" else "it is refused") {
                        resolved shouldBe if (opens) intent else null
                    }
                }
            }
        }
    }

    given("a channel opened without an intent") {
        `when`("the default applies") {
            then("the App starts FAST_ACCESS and Keycloak WEB_SELECT_METHOD") {
                AuthIntent.fromRequest(null, ChannelType.APP) shouldBe AuthIntent.FAST_ACCESS
                AuthIntent.fromRequest(null, ChannelType.WEB) shouldBe AuthIntent.WEB_SELECT_METHOD
            }
        }
    }

    given("an intent name that does not exist") {
        `when`("a channel is opened with it") {
            then("it is refused on both channel types") {
                AuthIntent.fromRequest("nonsense", ChannelType.APP) shouldBe null
                AuthIntent.fromRequest("nonsense", ChannelType.WEB) shouldBe null
            }
        }
    }

    given("an intent a signed-in user starts from within a session") {
        inSession.forEach { intent ->
            `when`("${intent.name} is started on an ANONYMOUS channel") {
                then("it is refused for lack of a login") {
                    intent.startRefusal(ChannelState.ANONYMOUS, hasAccount = true) shouldBe AuthIntent.StartRefusal.NOT_LOGGED_IN
                }
            }
            `when`("${intent.name} is started on an AUTHENTICATED channel with an account") {
                then("it may start") {
                    intent.startRefusal(ChannelState.AUTHENTICATED, hasAccount = true) shouldBe null
                }
            }
        }
    }

    given("a process access, signed in without an account (ADR-48)") {
        `when`("it starts an intent from within its session") {
            then("only LOGOUT and STEP_UP may start; the account intents are refused") {
                inSession.associateWith { it.startRefusal(ChannelState.AUTHENTICATED, hasAccount = false) } shouldBe mapOf(
                    AuthIntent.STEP_UP to null,
                    AuthIntent.LOGOUT to null,
                    AuthIntent.MANAGE_AUTH_METHODS to AuthIntent.StartRefusal.NO_ACCOUNT,
                    AuthIntent.CONFIRM_PEER_LOGIN to AuthIntent.StartRefusal.NO_ACCOUNT,
                    AuthIntent.DELETE_ACCOUNT to AuthIntent.StartRefusal.NO_ACCOUNT
                )
            }
        }
    }

    given("an intent that is not started from within a session") {
        (AuthIntent.entries - inSession).forEach { intent ->
            `when`("${intent.name} is asked for") {
                then("it is refused as not startable there") {
                    intent.startRefusal(ChannelState.AUTHENTICATED, hasAccount = true) shouldBe AuthIntent.StartRefusal.NOT_STARTABLE_IN_SESSION
                }
            }
        }
    }
})
