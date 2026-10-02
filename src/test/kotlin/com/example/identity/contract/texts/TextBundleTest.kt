package com.example.identity.contract.texts

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

class TextBundleTest : BehaviorSpec({

    given("the app bundle") {
        val bundle = TextBundle("app")

        `when`("a client fetches the English texts for the first time") {
            val first = bundle.respond("en", null)

            then("the bundle comes with a strong ETag and must be revalidated") {
                first.statusCode shouldBe HttpStatus.OK
                first.headers.eTag shouldNotBe null
                first.headers.cacheControl shouldBe "no-cache"
                first.headers.getFirst(HttpHeaders.CONTENT_LANGUAGE) shouldBe "en"
            }
        }

        `when`("a client asks again with the ETag it got") {
            val eTag = bundle.respond("en", null).headers.eTag
            val again = bundle.respond("en", eTag)

            then("it answers 304 without a body") {
                again.statusCode shouldBe HttpStatus.NOT_MODIFIED
                again.body shouldBe null
                again.headers.eTag shouldBe eTag
            }
        }

        `when`("a client fetches German after English") {
            val english = bundle.respond("en", null)
            val german = bundle.respond("de", english.headers.eTag)

            then("German has another ETag, so the English one does not match it") {
                german.headers.eTag shouldNotBe english.headers.eTag
                german.statusCode shouldBe HttpStatus.OK
            }
        }

        `when`("a client asks for an unknown language") {
            val response = bundle.respond("fr", null)

            then("it falls back to German") {
                response.headers.getFirst(HttpHeaders.CONTENT_LANGUAGE) shouldBe "de"
            }
        }

        `when`("a client asks for a language with a region") {
            val response = bundle.respond("en-GB", null)

            then("the region is ignored") {
                response.headers.getFirst(HttpHeaders.CONTENT_LANGUAGE) shouldBe "en"
            }
        }
    }
})
