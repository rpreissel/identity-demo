package com.example.identity.core.orchestrator.schema

import com.example.identity.core.account.ClaimEncryptionKeys
import com.example.identity.core.account.application.Envelopes
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.DriverManager

/** ADR-55: a database keeps the encryption mode it was created with; the guard stops a start in the other mode before Flyway changes anything. */
class EncryptionModeGuardTest : BehaviorSpec({

    fun guard(encrypted: Boolean) = EncryptionModeGuard(encrypted)

    fun database(encrypted: Boolean?) = DriverManager.getConnection("jdbc:h2:mem:${java.util.UUID.randomUUID()}").also { connection ->
        connection.createStatement().use { it.execute("CREATE SCHEMA orchestrator") }
        if (encrypted != null) connection.createStatement().use {
            it.execute("CREATE TABLE orchestrator.encryption_mode (encrypted BOOLEAN NOT NULL)")
            it.execute("INSERT INTO orchestrator.encryption_mode VALUES ($encrypted)")
        }
    }

    given("a database created with encryption") {
        val connection = database(encrypted = true)

        then("an encrypted start passes") { shouldNotThrowAny { guard(true).check(connection) } }

        then("a demo start stops, naming both modes") {
            shouldThrow<EncryptionModeGuard.ModeMismatch> { guard(false).check(connection) }
                .message!! shouldContain "mit Verschlüsselung angelegt"
        }
    }

    given("a readable demo database") {
        val connection = database(encrypted = false)

        then("an encrypted start stops") { shouldThrow<EncryptionModeGuard.ModeMismatch> { guard(true).check(connection) } }
    }

    given("a database before its first migration") {
        val connection = database(encrypted = null)

        then("either mode passes - the migration records it") {
            shouldNotThrowAny { guard(false).check(connection) }
        }
    }

    given("the column widths") {
        then("the demo widens every sealed column by the header, and the digest holds the readable value") {
            ClaimEncryptionKeys.schemaPlaceholders(true)["secret_width"] shouldBe "288"
            ClaimEncryptionKeys.schemaPlaceholders(false)["secret_width"] shouldBe (288 + Envelopes.HEADER_ALLOWANCE).toString()
            ClaimEncryptionKeys.schemaPlaceholders(true)["digest_width"] shouldBe "64"
            ClaimEncryptionKeys.schemaPlaceholders(true)["encryption_enabled"] shouldBe "true"
        }
    }
})
