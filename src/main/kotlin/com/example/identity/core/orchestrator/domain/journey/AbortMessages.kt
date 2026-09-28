package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.core.orchestrator.domain.policy.UnreachableReason
import com.example.identity.contract.tool_api.FactorType

/**
 * Turns a policy-level [Reachability]/[UnreachableReason] into the user-facing [Transition.Abort]
 * text. `AuthPolicy` only produces a structured reason; this file is the one place where a reason
 * becomes words, so the wording can change without touching reason-computing code.
 */
fun Reachability.NotReachable.toAbortMessage(): Text =
    Text("Mit Ihren Anmeldeverfahren ist das nötige Sicherheitsniveau nicht erreichbar. {grund}", "grund" to reason.toText())

/**
 * The abort text once [CandidateTools.forAuth] and [CandidateTools.forReIdentification] both came
 * back empty. Only then does [Reachability.Reachable] mean "the account could do this, just not on
 * this channel right now", e.g. a method already used this session or bound to another device.
 */
fun Reachability.toAuthAbortMessage(): Text = when (this) {
    is Reachability.NotReachable -> toAbortMessage()

    Reachability.Reachable -> Text(
        "Hier steht gerade kein passendes Anmeldeverfahren zur Verfügung - etwa weil es in dieser Sitzung schon genutzt " +
            "wurde oder an ein anderes Gerät gebunden ist."
    )
}

/**
 * The abort text once [CandidateTools.forEnrollment] came back empty. [Reachability.Reachable] here
 * means "the account could still enroll something, just not on this channel right now"; it is worded
 * differently from the auth case in [toAuthAbortMessage].
 */
fun Reachability.toEnrollAbortMessage(): Text = when (this) {
    is Reachability.NotReachable -> toAbortMessage()

    Reachability.Reachable -> Text("Hier steht gerade kein weiteres Verfahren zur Einrichtung zur Verfügung.")
}

private fun UnreachableReason.toText(): Text = when (this) {
    UnreachableReason.NoActiveMethod -> Text("Für dieses Konto ist derzeit kein aktives Anmeldeverfahren eingerichtet.")

    is UnreachableReason.SingleFactorType -> Text(
        "Ihre Verfahren ({methods}) sind alle von derselben Art ({faktoren}). Richten Sie zusätzlich ein Verfahren " +
            "anderer Art ein, zum Beispiel ein Passwort.",
        "methods" to methods.joinToString(", "),
        "faktoren" to factorTypes.map { it.toText() }
    )

    // Not which level they were set up under - the reader needs only what to do next.
    is UnreachableReason.CombinationCapped ->
        Text("Ihre Verfahren wurden mit einem niedrigeren Sicherheitsniveau eingerichtet. Identifizieren Sie sich und richten Sie danach ein neues Verfahren ein.")

    is UnreachableReason.SingleMethodCapped -> Text(
        "Das Verfahren {method} wurde mit einem niedrigeren Sicherheitsniveau eingerichtet. Identifizieren Sie sich " +
            "und richten Sie es danach erneut ein.",
        "method" to method
    )
}

private fun FactorType.toText(): Text = when (this) {
    FactorType.KNOWLEDGE -> Text("Wissen")
    FactorType.POSSESSION -> Text("Besitz")
    FactorType.INHERENCE -> Text("Inhärenz")
}
