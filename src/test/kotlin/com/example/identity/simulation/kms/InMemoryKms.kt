package com.example.identity.simulation.kms

import com.example.identity.TEST_CLOCK
import com.example.identity.simulation.kms.internal.KmsKey
import com.example.identity.simulation.kms.internal.KmsKeyRepository
import com.example.identity.simulation.kms.internal.KmsKeyVersion
import com.example.identity.simulation.kms.internal.KmsKeyVersionId
import com.example.identity.simulation.kms.internal.KmsKeyVersionRepository
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

/** The simulated KMS over maps instead of tables, for unit tests of everything that keys with it. */
class InMemoryKms {
    val keys = mutableMapOf<String, KmsKey>()
    val versions = mutableMapOf<KmsKeyVersionId, KmsKeyVersion>()

    private val keyRepository = mockk<KmsKeyRepository> {
        every { findById(any()) } answers { Optional.ofNullable(keys[firstArg()]) }
        every { save(any<KmsKey>()) } answers { firstArg<KmsKey>().also { keys[it.name!!] = it } }
        every { findAll() } answers { keys.values.toList() }
    }
    private val versionRepository = mockk<KmsKeyVersionRepository> {
        every { findById(any()) } answers { Optional.ofNullable(versions[firstArg()]) }
        every { save(any<KmsKeyVersion>()) } answers { firstArg<KmsKeyVersion>().also { versions[KmsKeyVersionId(it.keyName, it.version)] = it } }
        every { findByKeyNameOrderByVersion(any()) } answers { versions.values.filter { it.keyName == firstArg() }.sortedBy { it.version } }
        every { delete(any<KmsKeyVersion>()) } answers { versions.remove(KmsKeyVersionId(firstArg<KmsKeyVersion>().keyName, firstArg<KmsKeyVersion>().version)); Unit }
    }

    val transit = KmsTransit(keyRepository, versionRepository, TEST_CLOCK)
}
