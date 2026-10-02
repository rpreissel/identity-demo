package com.example.identity.architecture

import com.example.identity.tools.auth_email.internal.EmailSendLimit
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import com.example.identity.contract.tool_api.ModuleId
import com.example.identity.contract.tool_api.ratelimit.RateLimit
import com.example.identity.contract.tool_api.ratelimit.RateLimits
import com.example.identity.simulation.mail.MailServer
import com.example.identity.simulation.sms.SmsGateway
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Mechanism in the orchestrator, policy in the modules (ADR-44). A module counts only in its own
 * namespace: the namespace comes from the budget's class, and every count goes through a budget.
 * A class that sends a code also asks its module's send budget.
 */
class RateLimitArchitectureTest : BehaviorSpec({

    val budgets = MAIN_CLASSES.filter { it.isAssignableTo(RateLimit::class.java) && it.name != RateLimit::class.java.name }

    given("the budgets of the tool modules") {
        then("they exist") {
            budgets.shouldNotBeEmpty()
        }

        then("each is a named class in a tool module, never in tool_api or the orchestrator") {
            val offenders = budgets.filter { budget ->
                budget.isAnonymousClass || !budget.packageName.startsWith(TOOLS)
            }
            offenders.map { it.name }.shouldBeEmpty()
        }

        then("their namespaces are distinct and fit the scope column (64)") {
            val namespaces = budgets.map { RateLimit.namespaceOf(it.reflect()) }
            namespaces.filter { it.length > 64 }.shouldBeEmpty()
            namespaces.toSet().size shouldBe namespaces.size
        }
    }

    given("the classes that send a code") {
        // Sender -> the budget its module bounds that sender with.
        val sendBoundBy = mapOf(
            SmsGateway::class.java.name to SmsSendLimit::class.java.name,
            MailServer::class.java.name to EmailSendLimit::class.java.name,
        )

        then("each also asks its module's send budget") {
            val offenders = MAIN_CLASSES.filter { it.packageName.startsWith(TOOLS) }.flatMap { source ->
                val calls = source.methodCallsFromSelf
                val asksBudget = calls.filter { it.name == "trySend" }.map { it.targetOwner.name }.toSet()
                calls.map { it.targetOwner.name }.toSet()
                    .mapNotNull { sender -> sendBoundBy[sender]?.takeIf { it !in asksBudget }?.let { "${source.name} -> $sender" } }
            }
            offenders.shouldBeEmpty()
        }
    }

    given("the counting port") {
        then("only RateLimit calls it, so no count bypasses a namespace") {
            val callers = MAIN_CLASSES
                .flatMap { it.methodCallsFromSelf }
                .filter { it.targetOwner.isAssignableTo(RateLimits::class.java) }
                .filterNot { it.originOwner.isAssignableTo(RateLimits::class.java) }
                .map { it.originOwner.name }
                .filter { it != RateLimit::class.java.name }
                .distinct()
            callers.shouldBeEmpty()
        }
    }
}) {
    private companion object {
        /** The group of the tool modules, the only ones that own a budget. */
        const val TOOLS = "${ModuleId.ROOT_PACKAGE}.tools."
    }
}
