package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/**
 * Identity matching trusts a KVNR only because the Personenverzeichnis vouched for it. A tool
 * that declares one from anywhere else cannot be registered on its module.
 */
class KvnrSourceRuleTest : BehaviorSpec({

    given("the real catalog") {
        `when`("its modules are built") {
            then("they are accepted - every KVNR comes from the Personenverzeichnis") {
                shouldNotThrowAny { StrategyTestFixtures.catalog }
            }
        }
    }

    given("an identification that reads a KVNR itself") {
        `when`("the tool is registered on its module") {
            val result = runCatching {
                toolModule(method = "card-reader", proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA2))
                    .identify("ident-card-reader", also = setOf(AttributeType.KVNR))
            }

            then("it is refused") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }.message shouldContain "ident-card-reader"
            }
        }
    }

    given("an identification whose KVNR the Personenverzeichnis vouches for") {
        `when`("the tool is registered on its module") {
            then("it is accepted") {
                shouldNotThrowAny {
                    toolModule(method = "register-lookup", proves = factors(FactorType.POSSESSION, upTo = AcrLevel.LOA2))
                        .identify("ident-register-lookup", also = setOf(AttributeType.KVNR), vouchedBy = ClaimSource.PERSON_DIRECTORY)
                }
            }
        }
    }
})
