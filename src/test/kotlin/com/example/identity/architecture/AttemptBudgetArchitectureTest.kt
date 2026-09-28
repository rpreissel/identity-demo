package com.example.identity.architecture

import com.example.identity.tools.auth_email.internal.EmailSendBudget
import com.example.identity.tools.auth_sms.internal.SmsSendBudget
import com.example.identity.contract.tool_api.ModuleId
import com.example.identity.contract.tool_api.budget.AttemptBudget
import com.example.identity.contract.tool_api.budget.AttemptBudgets
import com.example.identity.simulation.mail.MailServer
import com.example.identity.simulation.sms.SmsGateway
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Mechanism in the orchestrator, policy in the modules (ADR-44). A module counts only in its own
 * namespace: the namespace comes from the budget's class, and every count goes through a budget.
 * A class that sends a code also asks its module's send budget.
 */
class AttemptBudgetArchitectureTest : BehaviorSpec({

    val everything = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.identity")

    val budgets = everything.filter { it.isAssignableTo(AttemptBudget::class.java) && it.name != AttemptBudget::class.java.name }

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
            val namespaces = budgets.map { AttemptBudget.namespaceOf(it.reflect()) }
            namespaces.filter { it.length > 64 }.shouldBeEmpty()
            namespaces.toSet().size shouldBe namespaces.size
        }

        then("a namespace names the module and the class") {
            AttemptBudget.namespaceOf(SmsSendBudget::class.java) shouldBe "auth_sms.SmsSendBudget"
            AttemptBudget.namespaceOf(EmailSendBudget::class.java) shouldBe "auth_email.EmailSendBudget"
        }
    }

    given("the classes that send a code") {
        // Sender -> the budget its module bounds that sender with.
        val sendBoundBy = mapOf(
            SmsGateway::class.java.name to SmsSendBudget::class.java.name,
            MailServer::class.java.name to EmailSendBudget::class.java.name,
        )

        then("each also asks its module's send budget") {
            val offenders = everything.filter { it.packageName.startsWith(TOOLS) }.flatMap { source ->
                val calls = source.methodCallsFromSelf
                val asksBudget = calls.filter { it.name == "trySend" }.map { it.targetOwner.name }.toSet()
                calls.map { it.targetOwner.name }.toSet()
                    .mapNotNull { sender -> sendBoundBy[sender]?.takeIf { it !in asksBudget }?.let { "${source.name} -> $sender" } }
            }
            offenders.shouldBeEmpty()
        }
    }

    given("the counting port") {
        then("only AttemptBudget calls it, so no count bypasses a namespace") {
            val callers = everything
                .flatMap { it.methodCallsFromSelf }
                .filter { it.targetOwner.isAssignableTo(AttemptBudgets::class.java) }
                .filterNot { it.originOwner.isAssignableTo(AttemptBudgets::class.java) }
                .map { it.originOwner.name }
                .filter { it != AttemptBudget::class.java.name }
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
