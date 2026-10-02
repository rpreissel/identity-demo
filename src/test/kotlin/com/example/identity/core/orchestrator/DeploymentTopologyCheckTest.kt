package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

/** The single-instance claim is checked, not assumed (docs/07-betrieb.md Abschnitt 3b). */
class DeploymentTopologyCheckTest : BehaviorSpec({

    given("a single instance with the pepper left to chance") {
        val check = DeploymentTopologyCheck(DeploymentProperties(DeploymentProperties.Instances.SINGLE), otpPepper = "")

        `when`("the topology is checked") {
            val result = runCatching { check.check() }

            then("it starts") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }

    given("a claim of several instances without a shared pepper") {
        val check = DeploymentTopologyCheck(DeploymentProperties(DeploymentProperties.Instances.MULTIPLE), otpPepper = "")

        `when`("the topology is checked") {
            val result = runCatching { check.check() }

            then("it refuses and names what is still missing - the jobs by name, the per-process secrets") {
                val failure = shouldThrow<IllegalStateException> { result.getOrThrow() }
                failure.message!! shouldContain "otp-pepper"
                failure.message!! shouldContain "RestoreDataCodec"
                SCHEDULED_JOBS.keys.forEach { failure.message!! shouldContain it }
            }
        }
    }

    given("a claim of several instances with a shared pepper") {
        val check = DeploymentTopologyCheck(DeploymentProperties(DeploymentProperties.Instances.MULTIPLE), otpPepper = "x".repeat(32))

        `when`("the topology is checked") {
            val result = runCatching { check.check() }

            then("it still refuses - the jobs have no lock") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message!! shouldContain "RetentionJob"
            }
        }
    }
})
