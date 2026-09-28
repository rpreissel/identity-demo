package com.example.identity.kcext.grant;

import com.example.identity.kcext.OrchestratorNotes;

import java.util.regex.Pattern;

/**
 * Die Form von acr und amr, die der Account-Token-Grant in die Sitzung schreibt. Der Orchestrator
 * bleibt die Autoritaet (ADR-9); die Pruefung haelt nur Werte aus dem Token fern, die er nie schickt.
 */
final class AccountTokenClaims {

    /** Ein amr-Wert ist ein Methodenname; der Mapper trennt die Liste am Komma. */
    private static final Pattern AMR_VALUE = Pattern.compile("[a-z0-9_-]+");

    private AccountTokenClaims() {
    }

    /**
     * Grund der Ablehnung oder {@code null}: acr fehlt oder ist ein Niveau des Realms, amr ist leer
     * oder eine kommagetrennte Liste von Methodennamen.
     */
    static String problem(String acr, String amr) {
        if (acr != null && !acr.isBlank() && !OrchestratorNotes.isKnownAcr(acr)) {
            return "Unknown " + AccountTokenGrantType.ACR_PARAM;
        }
        if (amr != null && !amr.isEmpty()) {
            for (String value : amr.split(",", -1)) {
                if (!AMR_VALUE.matcher(value).matches()) return "Malformed " + AccountTokenGrantType.AMR_PARAM;
            }
        }
        return null;
    }
}
