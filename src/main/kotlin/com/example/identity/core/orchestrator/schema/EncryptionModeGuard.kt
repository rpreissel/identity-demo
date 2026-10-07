package com.example.identity.core.orchestrator.schema

import com.example.identity.core.account.ClaimEncryptionKeys
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.sql.Connection

/**
 * A database is either encrypted or a readable demo, never both (ADR-55). The sealed columns are
 * as wide as the mode needs (`${secret_width}` and friends in the migrations), and the first
 * migration run records the mode in `orchestrator.encryption_mode`. Before any later run this
 * callback compares that row with the running mode and stops Flyway, changing nothing, if they
 * differ: a switched mode on existing data would leave every digest incomparable and every value
 * unreadable.
 */
@Configuration
// Reads the switch itself: the account module's facade needs the entity manager, which waits for Flyway.
class EncryptionModeGuard(@Value("\${identity.encryption.enabled:true}") private val encryptionEnabled: Boolean) {

    /** Thrown before anything is migrated; `FlywayResetConfig` lets it through instead of wiping the demo database. */
    class ModeMismatch(databaseEncrypted: Boolean) : IllegalStateException(
        "Die Datenbank wurde ${if (databaseEncrypted) "mit" else "ohne"} Verschlüsselung angelegt, die Anwendung läuft " +
            "${if (databaseEncrypted) "ohne" else "mit"} (identity.encryption.enabled). Nichts geändert: Modus anpassen oder die Datenbank neu aufbauen."
    )

    @Bean
    fun encryptionModePlaceholders(): FlywayConfigurationCustomizer = FlywayConfigurationCustomizer { configuration ->
        configuration.placeholders(configuration.placeholders + ClaimEncryptionKeys.schemaPlaceholders(encryptionEnabled))
        configuration.callbacks(*configuration.callbacks, guard)
    }

    private val guard = object : Callback {
        override fun supports(event: Event, context: Context?) = event == Event.BEFORE_MIGRATE
        override fun canHandleInTransaction(event: Event, context: Context?) = true
        override fun handle(event: Event, context: Context) = check(context.connection)
        override fun getCallbackName() = "encryption-mode-guard"
    }

    internal fun check(connection: Connection) {
        val stored = storedMode(connection) ?: return
        if (stored != encryptionEnabled) throw ModeMismatch(stored)
    }

    private fun storedMode(connection: Connection): Boolean? {
        connection.metaData.getTables(null, "ORCHESTRATOR", "ENCRYPTION_MODE", null).use { if (!it.next()) return null }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT encrypted FROM orchestrator.encryption_mode").use { rows ->
                return if (rows.next()) rows.getBoolean(1) else null
            }
        }
    }
}
