package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.dao.DataIntegrityViolationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The database constraints of docs/invarianten.md and docs/02-domaenenmodell.md really bite: each
 * rule is broken directly in SQL, past all code, and the database must refuse. Plus the one place
 * where SQL repeats code knowledge - the list of methods that allow several instances - checked
 * against the tool descriptors, so the two cannot drift apart.
 */
class DatabaseInvariantConstraintTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun channelId(): UUID = UUID.fromString(post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String)

    private fun insertJourney(channel: UUID, lifecycle: String) = jdbcTemplate.update(
        """INSERT INTO orchestrator.auth_journey (id, channel_session_id, intent, lifecycle, state_type, state, attempt_budget, created_at, expires_at)
           VALUES (?, ?, 'LOGIN', ?, 'x', '{}', 3, CURRENT_TIMESTAMP, DATEADD('HOUR', 1, CURRENT_TIMESTAMP))""",
        UUID.randomUUID(), channel, lifecycle
    )

    /** The write failed on [constraint], not on some other rule it happened to break as well. */
    private fun Result<*>.shouldBeRefusedBy(constraint: String) {
        shouldThrow<DataIntegrityViolationException> { getOrThrow() }.message.orEmpty().uppercase() shouldContain constraint.uppercase()
    }

    private fun insertAppTokenSession(): UUID = UUID.randomUUID().also {
        jdbcTemplate.update(
            "INSERT INTO orchestrator.app_token_session (id, account_id, auth_time, updated_at) VALUES (?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", it
        )
    }

    private fun insertAuthMethod(accountId: Long, active: Boolean, deactivated: Boolean) = jdbcTemplate.update(
        """INSERT INTO account.auth_method (id, account_id, method, enrollment_type, enrollment_id, active, created_at, deactivated_at)
           VALUES (?, ?, 'device', 't', ?, ?, CURRENT_TIMESTAMP, ${if (deactivated) "CURRENT_TIMESTAMP" else "NULL"})""",
        UUID.randomUUID(), accountId, UUID.randomUUID().toString(), active
    )

    init {
        given("I-3: a channel with its STARTED entry journey") {
            `when`("a finished journey and then a second STARTED one are inserted") {
                val channel = channelId()
                val finished = runCatching { insertJourney(channel, "CANCELLED") }
                val second = runCatching { insertJourney(channel, "STARTED") }

                then("the finished one is fine") {
                    finished.isSuccess shouldBe true
                }
                then("the second STARTED journey is refused") {
                    shouldThrow<DataIntegrityViolationException> { second.getOrThrow() }
                }
            }
        }

        given("I-1: a channel and some session evidence") {
            `when`("the channel is ended while keeping the evidence") {
                val channel = channelId()
                val evidence = UUID.randomUUID()
                jdbcTemplate.update(
                    "INSERT INTO orchestrator.session_evidence (id, account_id, methods, updated_at) VALUES (?, 1, '[]', CURRENT_TIMESTAMP)", evidence
                )

                val result = runCatching {
                    jdbcTemplate.update(
                        "UPDATE orchestrator.channel_session SET state = 'LOGGED_OUT', session_evidence_id = ? WHERE id = ?", evidence, channel
                    )
                }

                then("the database refuses it") {
                    shouldThrow<DataIntegrityViolationException> { result.getOrThrow() }
                }
            }
        }

        given("I-4: a channel") {
            `when`("it is set AUTHENTICATED without evidence") {
                val channel = channelId()

                val result = runCatching {
                    jdbcTemplate.update("UPDATE orchestrator.channel_session SET state = 'AUTHENTICATED', session_evidence_id = NULL WHERE id = ?", channel)
                }

                then("the database refuses it") {
                    shouldThrow<DataIntegrityViolationException> { result.getOrThrow() }
                }
            }
        }

        given("I-13: an account with two active devices and an active password") {
            `when`("a second active password is inserted") {
                val accountId = accountFixtures.seedAccount(methods = emptyList())
                fun insertMethod(method: String) = jdbcTemplate.update(
                    """INSERT INTO account.auth_method (id, account_id, method, enrollment_type, enrollment_id, active, created_at)
                       VALUES (?, ?, ?, 't', ?, TRUE, CURRENT_TIMESTAMP)""",
                    UUID.randomUUID(), accountId.value, method, UUID.randomUUID().toString()
                )
                val singletonsAndMultiples = runCatching {
                    insertMethod("device")
                    insertMethod("device")
                    insertMethod("password")
                }

                val secondPassword = runCatching { insertMethod("password") }

                then("a second device was fine") {
                    singletonsAndMultiples.isSuccess shouldBe true
                }
                then("the second password is refused") {
                    shouldThrow<DataIntegrityViolationException> { secondPassword.getOrThrow() }
                }
            }
        }

        given("I-1: a channel holding an app token session") {
            `when`("the channel is ended while keeping the token session") {
                val channel = channelId()
                val tokenSession = insertAppTokenSession()

                val result = runCatching {
                    jdbcTemplate.update(
                        "UPDATE orchestrator.channel_session SET state = 'EXPIRED', app_token_session_id = ? WHERE id = ?", tokenSession, channel
                    )
                }

                then("the database refuses it") {
                    result.shouldBeRefusedBy("ck_channel_session_ended_without_login")
                }
            }
        }

        given("I-8: an App channel bound to its key") {
            `when`("its key is dropped") {
                val channel = channelId()

                val result = runCatching {
                    jdbcTemplate.update("UPDATE orchestrator.channel_session SET binding_key_ref = NULL WHERE id = ?", channel)
                }

                then("the database refuses an App channel without a key") {
                    result.shouldBeRefusedBy("ck_channel_session_binding_key")
                }
            }

            `when`("it is turned into a Web channel that keeps the key") {
                val channel = channelId()

                val result = runCatching {
                    jdbcTemplate.update("UPDATE orchestrator.channel_session SET channel = 'WEB' WHERE id = ?", channel)
                }

                then("the database refuses a Web channel with a key") {
                    result.shouldBeRefusedBy("ck_channel_session_binding_key")
                }
            }
        }

        given("I-5: a channel") {
            `when`("it is given an account and an invitation at once") {
                val channel = channelId()

                val result = runCatching {
                    jdbcTemplate.update("UPDATE orchestrator.channel_session SET account_id = 1, invitation = 'inv-1' WHERE id = ?", channel)
                }

                then("the database refuses a channel with two subjects") {
                    result.shouldBeRefusedBy("ck_channel_session_one_subject")
                }
            }
        }

        given("I-5: session evidence") {
            fun insertEvidence(accountId: Long?, invitation: String?) = runCatching {
                jdbcTemplate.update(
                    "INSERT INTO orchestrator.session_evidence (id, account_id, invitation, methods, updated_at) VALUES (?, ?, ?, '[]', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), accountId, invitation
                )
            }

            `when`("evidence for an account and an invitation at once is inserted") {
                val result = insertEvidence(1L, "inv-1")

                then("the database refuses it") {
                    result.shouldBeRefusedBy("ck_session_evidence_one_subject")
                }
            }

            `when`("evidence without any subject is inserted") {
                val result = insertEvidence(null, null)

                then("the database refuses it") {
                    result.shouldBeRefusedBy("ck_session_evidence_one_subject")
                }
            }
        }

        given("I-5: the sign-in log") {
            fun insertSignIn(accountId: Long?, invitation: String?) = runCatching {
                jdbcTemplate.update(
                    "INSERT INTO account.sign_in_log (account_id, invitation, sign_in_type, occurred_at) VALUES (?, ?, 'SIGNED_IN', CURRENT_TIMESTAMP)",
                    accountId, invitation
                )
            }

            `when`("a row for an account and an invitation at once is inserted") {
                val accountId = accountFixtures.seedAccount(methods = emptyList())
                val result = insertSignIn(accountId.value, "inv-1")

                then("the database refuses it") {
                    result.shouldBeRefusedBy("ck_sign_in_log_one_subject")
                }
            }

            `when`("a row without any subject is inserted") {
                val result = insertSignIn(null, null)

                then("the database refuses it") {
                    result.shouldBeRefusedBy("ck_sign_in_log_one_subject")
                }
            }
        }

        given("an account with method instances (docs/02-domaenenmodell.md Abschnitt 7)") {
            `when`("instances whose active flag and deactivation time agree or disagree are inserted") {
                val accountId = accountFixtures.seedAccount(methods = emptyList()).value
                val consistent = runCatching {
                    insertAuthMethod(accountId, active = true, deactivated = false)
                    insertAuthMethod(accountId, active = false, deactivated = true)
                }

                val activeButDeactivated = runCatching { insertAuthMethod(accountId, active = true, deactivated = true) }
                val inactiveWithoutTime = runCatching { insertAuthMethod(accountId, active = false, deactivated = false) }

                then("an active instance without and an inactive one with a deactivation time are fine") {
                    consistent.isSuccess shouldBe true
                }
                then("an active instance with a deactivation time is refused") {
                    activeButDeactivated.shouldBeRefusedBy("ck_auth_method_deactivation")
                }
                then("an inactive instance without a deactivation time is refused") {
                    inactiveWithoutTime.shouldBeRefusedBy("ck_auth_method_deactivation")
                }
            }
        }

        // The register's seed data stays between scenarios, so the `when` removes its person again.
        given("a person in the register with a Freischaltcode (docs/02-domaenenmodell.md Abschnitt 7)") {
            val person = "P999999990"
            fun insertInvitation(id: String, niveau: String) = jdbcTemplate.update(
                """INSERT INTO personenverzeichnis.einladung (id, person_id, vorgang, niveau, gueltig_bis, ausgestellt_am)
                   VALUES (?, ?, 'vorgang-1', ?, DATEADD('DAY', 7, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP)""",
                id, person, niveau
            )
            fun insertLetter(freischaltcodeId: Long?, einladungId: String?) = jdbcTemplate.update(
                "INSERT INTO personenverzeichnis.brief (person_id, freischaltcode_id, einladung_id, code, versandt_am) VALUES (?, ?, ?, 'code', CURRENT_TIMESTAMP)",
                person, freischaltcodeId, einladungId
            )

            `when`("letters and invitations are inserted, some breaking the register's rules") {
                jdbcTemplate.update("INSERT INTO personenverzeichnis.person (id) VALUES (?)", person)
                jdbcTemplate.update(
                    "INSERT INTO personenverzeichnis.freischaltcode (person_id, code_hash, expires_at) VALUES (?, 'hash', DATEADD('DAY', 7, CURRENT_TIMESTAMP))", person
                )
                val freischaltcode = jdbcTemplate.queryForObject(
                    "SELECT id FROM personenverzeichnis.freischaltcode WHERE person_id = ?", Long::class.java, person
                )
                val valid = runCatching {
                    insertInvitation("einladung-1", "loa2")
                    insertLetter(freischaltcode, null)
                    insertLetter(null, "einladung-1")
                }

                val letterWithBoth = runCatching { insertLetter(freischaltcode, "einladung-1") }
                val letterWithNeither = runCatching { insertLetter(null, null) }
                val invitationAtLoa3 = runCatching { insertInvitation("einladung-2", "loa3") }
                jdbcTemplate.update("DELETE FROM personenverzeichnis.person WHERE id = ?", person)

                then("a letter with exactly one code and an invitation at loa2 are fine") {
                    valid.isSuccess shouldBe true
                }
                then("a letter with a Freischaltcode and an invitation is refused") {
                    letterWithBoth.shouldBeRefusedBy("ck_brief_ein_code")
                }
                then("a letter with neither is refused") {
                    letterWithNeither.shouldBeRefusedBy("ck_brief_ein_code")
                }
                then("an invitation above loa2 is refused") {
                    invitationAtLoa3.shouldBeRefusedBy("ck_einladung_niveau")
                }
            }
        }

        // The one place where SQL repeats code knowledge; no action, so no `when`.
        given("the SQL list of multi-instance methods and the tool descriptors") {
            val fromDescriptors = toolRegistry.descriptors().filter { it.allowsMultipleInstances }.map { it.method }.toSet()
            val migration = Files.readString(
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("src/main/resources/db/migration/account/V23__eine_aktive_singleton_methode.sql") }
                    .first { Files.exists(it) }
            )
            val fromSql = Regex("""method NOT IN \(([^)]*)\)""").findAll(migration)
                .map { match -> match.groupValues[1].split(",").map { it.trim().trim('\'') }.toSet() }
                .toSet()

            then("they match") {
                fromSql shouldBe setOf(fromDescriptors)
            }
        }
    }
}
