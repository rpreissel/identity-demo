package com.example.identity.contract.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper

class TextTest : BehaviorSpec({

    val json = jacksonObjectMapper()

    // These templates are test-only, so not in the application's catalog the runtime guard checks.
    val guard = Text.onWire
    beforeSpec { Text.onWire = null }
    afterSpec { Text.onWire = guard }

    given("one template with different values") {
        then("the same template is the same id, whatever the values") {
            Text("Dieser {typ}-Wert", "typ" to "email").id shouldBe Text("Dieser {typ}-Wert", "typ" to "kvnr").id
        }
    }

    given("a list of texts as an argument") {
        then("it is one translated argument") {
            Text("Faktoren: {f}", "f" to listOf(Text("Besitz"), Text("Wissen"))).texts.getValue("f").size shouldBe 2
        }
    }

    given("the app bundle, loaded as the running application loads it") {
        TextBundle("app")

        `when`("a text the bundle words is serialized") {
            val wire = json.writeValueAsString(Text("Weiter"))

            then("it leaves as a reference - never as its source wording") {
                wire shouldBe """{"key":"${Text("Weiter").id}"}"""
            }
        }

        `when`("a text no bundle words yet is serialized") {
            val wire = json.writeValueAsString(Text("Retry-Limit erreicht: {grund}", "grund" to Text("TAN nie übersetzt"), "versuche" to 3))

            then("the template travels along - the client shows it instead of the key") {
                wire shouldBe """{"key":"${Text("Retry-Limit erreicht: {grund}").id}","args":{"versuche":"3"},""" +
                    """"texts":{"grund":[{"key":"${Text("TAN nie übersetzt").id}","template":"TAN nie übersetzt"}]},"template":"Retry-Limit erreicht: {grund}"}"""
            }
        }
    }

    given("a wording with placeholders") {
        then("they are its {name}s") {
            Text.placeholdersOf("Die {methods} decken {n} ab, {x y} nicht") shouldBe setOf("methods", "n")
        }
    }
})
