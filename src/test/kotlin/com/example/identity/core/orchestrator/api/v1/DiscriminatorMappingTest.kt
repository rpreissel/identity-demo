package com.example.identity.core.orchestrator.api.v1

import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockCredential
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The discriminator values in the contract must be the ones Jackson reads. The mapping is derived
 * by `JacksonSubTypesModelConverter`; if it silently stops running, a generated client sends the
 * schema name (`"BiometricUnlock"` instead of `"biometric"`) and fails only at runtime. So this
 * feeds every mapping key of the checked-in contract through Jackson.
 */
class DiscriminatorMappingTest : BehaviorSpec({

    val mapper = JsonMapper.builder().build()

    given("the checked-in contract") {
        then("every discriminator value deserializes to the class it is mapped to") {
            val spec = Yaml().load<Map<*, *>>(Files.readString(SNAPSHOT))
            val schemas = ((spec["components"] as Map<*, *>)["schemas"] as Map<*, *>)

            val unlock = schemas["KobilUnlockCredential"] as Map<*, *>
            val discriminator = unlock["discriminator"] as? Map<*, *>
                ?: error("KobilUnlockCredential hat keinen discriminator mehr - JacksonSubTypesModelConverter?")
            val property = discriminator["propertyName"] as String
            val mapping = discriminator["mapping"] as? Map<*, *>
                ?: error(
                    "discriminator.mapping fehlt. Ein Generator leitet die Werte dann aus den " +
                        "Schemanamen ab und sendet kind=\"BiometricUnlock\" statt \"biometric\"."
                )
            mapping.keys.shouldNotBeEmpty()

            mapping.forEach { (value, ref) ->
                val payload = """{"$property": "$value", "unlockSecret": "s", "password": "p"}"""
                val parsed = mapper.readValue(payload, KobilUnlockCredential::class.java)
                // Das Schema, auf das die Mapping-Zeile zeigt, muss die Klasse sein, die Jackson
                // fuer denselben Wert waehlt.
                parsed.javaClass.simpleName shouldBe (ref as String).substringAfterLast('/')
            }
        }
    }
}) {
    private companion object {
        private val SNAPSHOT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()
    }
}
