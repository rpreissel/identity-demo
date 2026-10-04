package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.dpop.DpopFailure
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.hibernate.exception.ConstraintViolationException
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.sql.SQLException

/**
 * How [OrchestratorExceptionHandler] answers each failure: the status and code of the error contract
 * (docs/07-betrieb.md #1), and never a detail the caller is not meant to see. Two requests racing on
 * the same AuthJourney surface as ObjectOptimisticLockingFailureException at commit time and map to 409.
 */
class OrchestratorExceptionHandlerTest : BehaviorSpec({

    given("an OrchestratorExceptionHandler") {
        val handler = OrchestratorExceptionHandler()

        /** The whole MVC path: the handler as controller advice, behind a controller that throws [failure]. */
        fun perform(failure: RuntimeException): MvcResult =
            MockMvcBuilders.standaloneSetup(FailingController(failure)).setControllerAdvice(handler).build()
                .perform(get("/failure")).andReturn()

        `when`("an OrchestratorException with BINDING_MISMATCH is handled") {
            val response = handler.handleOrchestratorException(OrchestratorException.bindingMismatch(Text("Caller proof does not match this channel")))

            then("it answers with the code's own status, 403") {
                response.statusCode shouldBe HttpStatus.FORBIDDEN
                response.body?.error shouldBe ErrorCode.BINDING_MISMATCH
            }
        }

        `when`("a proof and an assertion carry line breaks in alg and kid, and both are rejected") {
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
            val logger = org.slf4j.LoggerFactory.getLogger(OrchestratorExceptionHandler::class.java) as ch.qos.logback.classic.Logger
            logger.addAppender(appender)
            try {
                handler.handleDpopValidation(DpopValidationException(DpopFailure.UNSUPPORTED_ALGORITHM, "alg x\n2026-10-03 ERROR forged"))
                handler.handlePeerAuthValidation(PeerAuthValidationException("Unknown peer-auth key id: x\r\n2026-10-03 ERROR forged"))
            } finally {
                logger.detachAppender(appender)
            }

            then("the log gets them without control characters, so no line can be forged (I-19)") {
                appender.list.size shouldBe 2
                appender.list.forEach { it.formattedMessage shouldNotContain "\n"; it.formattedMessage shouldNotContain "\r" }
            }
        }

        `when`("a domain binding conflict is handled") {
            val response = handler.handleIdentityConflict(IdentityConflictException(Text("Person binding cannot change")))

            then("it uses the existing 409 contract") {
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.error shouldBe ErrorCode.INVALID_STATE_TRANSITION
            }
        }

        `when`("a rejected peer-auth assertion is handled") {
            val response = handler.handlePeerAuthValidation(PeerAuthValidationException("Unknown peer-auth key id: kid-4711"))

            then("it answers 401 with a neutral text, without key id or issuer") {
                response.statusCode shouldBe HttpStatus.UNAUTHORIZED
                response.body?.text shouldBe Text("Die Anfrage konnte nicht authentifiziert werden.")
            }
        }

        `when`("a rejected DPoP proof is handled") {
            val response = handler.handleDpopValidation(DpopValidationException(DpopFailure.UNSUPPORTED_ALGORITHM, "alg <script>"))

            then("it answers 401 with its fixed code, never the detail") {
                response.statusCode shouldBe HttpStatus.UNAUTHORIZED
                response.body?.text shouldBe Text("Die Anfrage konnte nicht authentifiziert werden ({detail}).", "detail" to "UNSUPPORTED_ALGORITHM")
            }
        }

        for (constraint in listOf("ux_anchor_value", "ux_anchor_account_type")) {
            `when`("a violation of the known unique constraint $constraint is handled") {
                val response = handler.handleConstraintViolation(
                    ConstraintViolationException("private SQL data", SQLException("private value", "23505"), constraint)
                )

                then("it maps to 409 without exposing SQL or values") {
                    response.statusCode shouldBe HttpStatus.CONFLICT
                    response.body?.error shouldBe ErrorCode.INVALID_STATE_TRANSITION
                    response.body?.text shouldBe Text("Diese Identität gehört bereits zu einem anderen Konto.")
                }
            }
        }

        for ((constraint, state) in listOf(
            "ux_unrelated" to "23505",
            "ux_anchor_extra" to "23505",
            "ux_anchor" to "23502",
            null to "23505"
        )) {
            `when`("an unrelated or unnamed integrity failure is handled: $constraint / $state") {
                val response = handler.handleConstraintViolation(
                    ConstraintViolationException("unrelated failure", SQLException("SQL error", state), constraint)
                )

                then("it is an internal error that reveals nothing") {
                    response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                    response.body?.error shouldBe ErrorCode.INTERNAL_ERROR
                    response.body?.text.toString() shouldNotContain "SQL"
                }
            }
        }

        val nestedViolation = ConstraintViolationException(
            "duplicate", SQLException("duplicate", "23505"), "ACCOUNT.UX_ANCHOR_VALUE INDEX ACCOUNT.UX_ANCHOR_VALUE_INDEX_2 ON ACCOUNT.ANCHOR(...)"
        )
        for ((at, failure) in listOf(
            "flush" to DataIntegrityViolationException("flush failed", nestedViolation),
            "commit" to TransactionSystemException("commit failed", nestedViolation),
        )) {
            `when`("MVC meets a binding violation nested in the failure at $at") {
                val result = perform(failure)

                then("it finds it and answers 409") {
                    result.response.status shouldBe 409
                    result.response.contentAsString shouldNotContain "UX_ANCHOR"
                    jsonPath("$.error").value("INVALID_STATE_TRANSITION").match(result)
                }
            }
        }

        `when`("MVC meets a failure no handler names - the database is down") {
            val result = perform(DataAccessResourceFailureException("jdbc:h2:file:/secret/path unreachable"))

            then("it still answers in the error contract, without the detail") {
                result.response.status shouldBe 500
                jsonPath("$.error").value("INTERNAL_ERROR").match(result)
                result.response.contentAsString shouldNotContain "secret"
            }
        }

        `when`("Spring's own web error for an unknown path is handled") {
            val notFound = NoResourceFoundException(HttpMethod.GET, "/nothing", "nothing")
            val result = runCatching { handler.handleUnexpected(notFound) }

            then("it is passed on unchanged, so it keeps its 404") {
                shouldThrow<NoResourceFoundException> { result.getOrThrow() } shouldBe notFound
            }
        }

        `when`("a broken internal assumption is handled") {
            val response = handler.handleIllegalState(IllegalStateException("auth-kobil enrollment 42 no longer exists"))

            then("it is a 500 whose text reveals nothing") {
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.error shouldBe ErrorCode.INTERNAL_ERROR
                response.body?.text.toString() shouldNotContain "42"
            }
        }

        `when`("input rejected without words of its own is handled") {
            val response = handler.handleIllegalArgument(IllegalArgumentException("Invalid UUID string: com.example.Internal"))

            then("it is a 400 with a neutral text - the message stays in the log") {
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body?.error shouldBe ErrorCode.BAD_REQUEST
                response.body?.text?.args shouldBe emptyMap()
            }
        }

        `when`("input rejected in the user's words is handled") {
            val words = Text("Ungueltige Telefonnummer")
            val response = handler.handleIllegalArgument(InvalidInputException(words))

            then("it keeps those words") {
                response.body?.text shouldBe words
            }
        }

        `when`("two requests race on the same AuthJourney and Hibernate throws ObjectOptimisticLockingFailureException") {
            val response = handler.handleConcurrentModification(ObjectOptimisticLockingFailureException("process_session", "some-id"))

            then("it maps to 409 Conflict with error CONCURRENT_MODIFICATION") {
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.error shouldBe ErrorCode.CONCURRENT_MODIFICATION
            }
        }
    }
}) {
    @RestController
    @Profile("exception-handler-test")
    class FailingController(private val failure: RuntimeException) {
        @GetMapping("/failure")
        fun fail(): String = throw failure
    }
}
