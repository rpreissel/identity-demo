package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.dao.DataIntegrityViolationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The database constraints of docs/invarianten.md (V22, V23) really bite: each rule is broken
 * directly in SQL, past all code, and the database must refuse. Plus the one place where SQL
 * repeats code knowledge - the list of methods that allow several instances - checked against the
 * tool descriptors, so the two cannot drift apart.
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
