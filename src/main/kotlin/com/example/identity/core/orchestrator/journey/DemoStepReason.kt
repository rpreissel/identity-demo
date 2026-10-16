package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.state.AnswerableState
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.WebSelectMethodState
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId

/**
 * Demo-only reasoning for why the innermost journey's current step looks the way it does: why a tool
 * became the automatic choice, or why a selection is shown. Kept out of the production [JourneyState]
 * hierarchy (docs/05-api.md #1: `demo` is the one non-production channel). It does not repeat the
 * screen's own text (`OfferingState.selectionTitle`, `Prompt`). Once a tool is [JourneyState.active]
 * the wording switches to past tense, since [OfferingState.activatable] ignores `active`.
 */
internal object DemoStepReason {
    fun explain(state: JourneyState, availableTools: Set<ToolId>): Text? {
        val activatable = state.activatable(availableTools)
        return when {
            activatable.isEmpty() -> null
            activatable.size == 1 -> when {
                state is FastAccessState.PreferredAuth -> Text("Gerät wiedererkannt - automatisch vorgeschlagen.")
                state is OfferingState && state.offered.size <= 1 -> Text("Nur dieses eine Verfahren ist verfügbar.")
                state is OfferingState -> Text("Die übrigen Verfahren sind aktuell nicht freigeschaltet.")
                else -> null
            }
            state is OfferingState && state.active == null -> Text("Mehrere aktive Verfahren vorhanden ({anzahl}) - zur Auswahl angeboten.", "anzahl" to activatable.size)
            state is OfferingState -> Text("Verfahren aus {anzahl} verfügbaren ausgewählt.", "anzahl" to activatable.size)
            else -> null
        }
    }

    /**
     * What this position of the journey is for, as opposed to [explain]'s "why this tool". Demo
     * text, so a state without one shows none rather than forcing words into the domain.
     */
    fun purpose(state: JourneyState): Text? = when (state) {
        is AuthChoice -> Text("Das Konto ist bekannt. Jetzt fehlt der Nachweis, dass Sie es sind - mit einem Verfahren, das schon eingerichtet ist.")
        is Enrolling -> Text("Das Konto hat noch kein Verfahren, das für künftige Anmeldungen reicht - deshalb wird jetzt eines eingerichtet.")

        FastAccessState.Start -> Text("Automatisch anmelden: die App nimmt den schnellsten Weg, den dieses Gerät erlaubt. Ohne verknüpftes Konto führt er über eine Registrierung.")
        is FastAccessState.PreferredAuth -> Text("Dieses Gerät ist mit einem Konto verbunden - der schnellste Weg ist das Verfahren dieses Geräts.")

        RegisterState.Start -> Text("Eine Registrierung beginnt mit der Frage, wer Sie sind - im Experiment „Einrichtung zuerst“ mit der E-Mail-Adresse.")
        RegisterState.Closing -> Text("Registrierung mit Einrichtung zuerst: Das Konto ist eingerichtet, die Identifizierung wurde freiwillig angeboten.")
        is RegisterState.Identifying -> Text("Für ein Konto muss feststehen, wer Sie sind - deshalb zuerst eine Identifizierung.")
        is RegisterState.Assigning -> Text("Die Identität steht fest. Mit der Versichertennummer lässt sie sich einer versicherten Person zuordnen - freiwillig.")
        is RegisterState.ConfirmingEmail -> Text("Ein neues Konto braucht eine bestätigte E-Mail-Adresse - sie ist Voraussetzung für ein Passwort.")
        is RegisterState.SecondFactorKindObligation -> Text("Damit Sie Ihre Anmeldeverfahren später verwalten können, braucht das Konto noch ein Verfahren anderer Art als die schon eingerichteten. Die Liste zeigt, welche hier passen.")


        LookupLoginState.Start -> Text("Anmeldung ohne Gerätebindung: Das Konto wird erst über Ihre Angaben gesucht.")
        is LookupLoginState.Credential -> Text("Anmeldung ohne Gerätebindung: E-Mail-Adresse oder Handynummer finden das Konto, das Verfahren beweist, dass Sie es sind.")
        is LookupLoginState.AdditionalFactor -> Text("Der erste Nachweis reicht nicht für das verlangte Sicherheitsniveau - ein zweites Verfahren fehlt.")
        is DeviceLinkQuestion -> when (state.wording) {
            DeviceLinkQuestion.Wording.OFFER -> Text("Anmeldung geschafft. Jetzt wird gefragt, ob dieses Gerät sich das Konto merken soll.")
            else -> Text("Dieses Gerät gehört schon zu einem anderen Konto - bevor es umgehängt wird, wird gefragt.")
        }

        is StepUpState -> stepUpPurpose(state.goal.targetAcr, state.goal.startingAcr)

        is FreshProofState.Start -> Text("Vor diesem Schritt wird geprüft, ob die Sitzung Sicherheitsniveau {ziel} hat und ihr letzter Nachweis frisch ist.", "ziel" to state.requiredAcr.value)
        is FreshProofState.Reproving -> Text("Der letzte Nachweis dieser Sitzung ist älter als fünf Minuten - vor dem nächsten Schritt muss ein Verfahren frisch bestätigt werden.")

        is ReIdentifyState.OfferReIdent -> Text("Kein vorhandenes Verfahren erreicht Sicherheitsniveau {ziel} - bleibt nur, sich neu auszuweisen.", "ziel" to state.targetAcr.value)
        is ReIdentifyState.Identifying -> Text("Neu ausweisen für Sicherheitsniveau {ziel} - es muss dieselbe Person herauskommen wie im Konto.", "ziel" to state.targetAcr.value)

        ManageAuthMethodsState.AddRequested -> Text("Sie wollen ein Verfahren hinzufügen - das verlangt Sicherheitsniveau 2 in dieser Sitzung.")
        is ManageAuthMethodsState.RemoveRequested -> Text("Sie wollen ein Verfahren entfernen - das verlangt Sicherheitsniveau 2 in dieser Sitzung.")
        is ManageAuthMethodsState.RetractAttributeRequested -> Text("Sie wollen eine bestätigte Angabe zurücknehmen - das verlangt Sicherheitsniveau 2 in dieser Sitzung.")
        is ManageAuthMethodsState.ChangeRequested -> Text("Sie wollen ein Verfahren ändern - das verlangt mindestens das Sicherheitsniveau, unter dem es eingerichtet wurde.")
        is ManageAuthMethodsState.Changing -> Text("Das Verfahren wird neu eingerichtet - der bisherige Eintrag bleibt, bis der neue fertig ist.")
        is ManageAuthMethodsState.Enrolling -> Text("Sicherheitsniveau 2 ist erreicht - jetzt wird das neue Verfahren eingerichtet.")

        DeleteAccountState.ConfirmPending -> Text("Löschen ist endgültig - deshalb zuerst eine Rückfrage.")


        is ConfirmPeerLoginState.Requested -> Text("Ein Browser wartet auf Freigabe durch dieses Gerät. Zuerst wird geprüft, ob diese Sitzung dafür sicher genug ist.")
        is ConfirmPeerLoginState.Confirming -> Text("Diese Sitzung ist sicher genug - jetzt geben Sie die wartende Anmeldung im Browser frei.")

        is WebSelectMethodState.SelectLoginMethod, is WebSelectMethodState.SelectStepUpMethod ->
            Text("Die Anmeldeseite verlangt einen Nachweis - angeboten wird jedes Verfahren, das im Browser geht.")
        is WebSelectMethodState.Start -> Text("Beginn oder zurück von der erneuten Identifizierung: Reichen die Nachweise für das verlangte Niveau, ist die Anmeldung fertig, sonst wird angeboten.")

        // AnswerableState is not sealed, so the compiler asks for this case; it only catches a
        // yes/no state added without a purpose of its own.
        is AnswerableState -> null
    }

    private fun stepUpPurpose(target: AcrLevel, starting: AcrLevel) = Text(
        "Verlangt ist Sicherheitsniveau {ziel}, diese Sitzung hat {stand} - ein weiterer Nachweis fehlt.",
        "ziel" to target.value,
        "stand" to starting.value,
    )
}
