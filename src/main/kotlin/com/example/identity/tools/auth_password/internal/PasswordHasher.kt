package com.example.identity.tools.auth_password.internal

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.UUID

/**
 * Password hashing with Argon2id and the OWASP parameters (19 MiB memory, 2 iterations, 1 lane).
 * If the parameters are raised, [upgrade] rehashes on the next successful login.
 */
internal object PasswordHasher {
    private val argon2 = Argon2PasswordEncoder(16, 32, 1, 19_456, 2)

    /**
     * A real hash of a value nobody knows, generated once per boot. Verifying against it costs
     * exactly what verifying against a genuine (Argon2id) credential costs, and can never match.
     */
    private val DUMMY_HASH: String = hash(UUID.randomUUID().toString())

    fun hash(password: String): String = checkNotNull(argon2.encode(password))

    /**
     * Always spends the full hashing work, whatever [stored] is: an early return would make "no
     * password here" measurably faster, an account-enumeration oracle. Missing hashes fall through
     * to [DUMMY_HASH]. Callers must not short-circuit this call either.
     */
    fun matches(candidate: String, stored: String?): Boolean = when {
        stored != null && stored.startsWith(ARGON2_PREFIX) -> argon2.matches(candidate, stored)
        else -> {
            argon2.matches(candidate, DUMMY_HASH)
            false
        }
    }

    /** Whether [stored] is an Argon2id hash with weaker parameters than today's. */
    fun needsRehash(stored: String?): Boolean =
        stored != null && stored.startsWith(ARGON2_PREFIX) && argon2.upgradeEncoding(stored)

    /**
     * After a SUCCESSFUL check only: moves [enrollment] to today's hash. Runs inside the caller's
     * transaction - the entity is managed, so the new hash is written with it.
     */
    fun upgrade(enrollment: AuthPasswordEnrollment, verifiedPassword: String) {
        if (needsRehash(enrollment.passwordHash)) enrollment.passwordHash = hash(verifiedPassword)
    }

    private const val ARGON2_PREFIX = "\$argon2"
}
