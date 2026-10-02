package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * What falls with a credential or an attribute, and the self-lockout guard on the channel's floor -
 * no Spring, no database (docs/adr/ADR-040-fachkern-im-paket-domain.md). `enroll-password` requires a
 * proven EMAIL; loa2 needs two factor types (sms: possession, password: knowledge).
 */
class MethodDependenciesTest : BehaviorSpec({

    val sms = StrategyTestFixtures.method("sms", AcrLevel.LOA2)
    val password = StrategyTestFixtures.method("password", AcrLevel.LOA2)
    val email = StrategyTestFixtures.method("email", AcrLevel.LOA1)
    val policy = StrategyTestFixtures.policy

    /** [claimsEmail] names the instances that asserted the EMAIL claim. */
    fun dependencies(vararg methods: AuthMethodView, claimsEmail: Set<AuthMethodView> = emptySet()) = MethodDependencies(
        StrategyTestFixtures.account(*methods), StrategyTestFixtures.catalog
    ) { if (it in claimsEmail) setOf(AttributeType.EMAIL) else emptySet() }

    given("an account with sms and password") {
        val deps = dependencies(sms, password)

        `when`("the password is removed on a channel whose floor is loa2") {
            then("it is refused - sms alone no longer reaches loa2, and nothing else falls") {
                deps.removal(password, policy, AcrLevel.LOA2) shouldBe Removal.BelowFloor(alsoFalling = emptyList())
            }
        }
        `when`("the password is removed on a channel whose floor is loa1") {
            then("only the password goes") {
                deps.removal(password, policy, AcrLevel.LOA1) shouldBe Removal.Allowed(listOf(password))
            }
        }
    }

    given("an account whose EMAIL claim only the email credential asserts, and a password that requires it") {
        val deps = dependencies(sms, password, email, claimsEmail = setOf(email))

        `when`("the email credential is removed on a channel whose floor is loa1") {
            then("the password falls with it, dependents first") {
                deps.removal(email, policy, AcrLevel.LOA1) shouldBe Removal.Allowed(listOf(password, email))
            }
        }
        `when`("the email credential is removed on a channel whose floor is loa2") {
            then("it is refused and names the password that would fall too") {
                deps.removal(email, policy, AcrLevel.LOA2) shouldBe Removal.BelowFloor(alsoFalling = listOf("password"))
            }
        }
    }

    given("an account whose EMAIL claim the sms credential also asserts") {
        val deps = dependencies(sms, password, email, claimsEmail = setOf(email, sms))

        `when`("the email credential is removed") {
            then("the password keeps its precondition and stays") {
                deps.removal(email, policy, AcrLevel.LOA2) shouldBe Removal.Allowed(listOf(email))
            }
        }
    }

    given("an account with sms and password, the EMAIL attribute withdrawn") {
        val deps = dependencies(sms, password)

        `when`("the channel's floor is loa1") {
            then("the password falls; the attribute itself is not a method") {
                deps.retraction(AttributeType.EMAIL, policy, AcrLevel.LOA1) shouldBe Removal.Allowed(listOf(password))
            }
        }
        `when`("the channel's floor is loa2") {
            then("it is refused and names the password") {
                deps.retraction(AttributeType.EMAIL, policy, AcrLevel.LOA2) shouldBe Removal.BelowFloor(alsoFalling = listOf("password"))
            }
        }
    }
})
