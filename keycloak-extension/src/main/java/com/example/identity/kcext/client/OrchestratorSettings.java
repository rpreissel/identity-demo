package com.example.identity.kcext.client;

import com.example.identity.kcext.model.OrchestratorComponent;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.idm.ComponentRepresentation;

import java.text.ParseException;
import java.util.List;

/**
 * Die Konfiguration dieser Extension, abgelegt als Config-Properties der
 * {@code orchestrator}-User-Storage-Komponente (gesetzt in V1__realm.kc.kts, ADR-25). Ohne
 * Fallback: Fehlt ein Wert, scheitert der Aufruf laut, statt einen Login still falsch laufen zu lassen.
 *
 * @param orchestratorBaseUrl Server-zu-Server-Adresse des Orchestrators (docs/05-api.md Abschnitt 3b).
 * @param publicOrchestratorBaseUrl Der Origin, den ein Browser aufloest. Nur fuer den
 *        Peer-Login-Deep-Link im QR-Code, der auf einem fremden Geraet geoeffnet wird
 *        (docs/verfahren/qr.md).
 * @param peerAuthIssuer {@code iss} der Peer-Auth-Assertion (docs/12-entscheidungen.md ADR-7).
 * @param peerAuthAudience {@code aud} derselben Assertion.
 * @param peerAuthSigningKey Signaturschluessel der Assertion. Er liegt an der Komponente, damit alle
 *        Knoten denselben nutzen und ein Neustart keine laufende Sitzung entwertet.
 */
public record OrchestratorSettings(
        String orchestratorBaseUrl,
        String publicOrchestratorBaseUrl,
        String peerAuthIssuer,
        String peerAuthAudience,
        ECKey peerAuthSigningKey
) {

    public static final String ORCHESTRATOR_BASE_URL = "orchestratorBaseUrl";
    public static final String PUBLIC_ORCHESTRATOR_BASE_URL = "publicOrchestratorBaseUrl";
    public static final String PEER_AUTH_ISSUER = "peerAuthIssuer";
    public static final String PEER_AUTH_AUDIENCE = "peerAuthAudience";

    /**
     * Der private Schluessel - siehe {@link #signingKey}. Als Geheimnis deklariert, damit Keycloak ihn
     * in Admin-API, Admin-Console und Realm-Export maskiert; ein Leeren erzeugt beim Speichern einen neuen.
     */
    static final String PEER_AUTH_SIGNING_KEY = "peerAuthSigningKeyJwk";

    /** Was die Admin-Console anzeigt, zugleich die Schluessel, die {@link #from(ComponentModel)} erwartet. */
    public static final List<ProviderConfigProperty> CONFIG_PROPERTIES = List.of(
            property(ORCHESTRATOR_BASE_URL, "Orchestrator base URL",
                    "Server-zu-Server: wie dieser Keycloak den Orchestrator erreicht, z.B. http://orchestrator:8080"),
            property(PUBLIC_ORCHESTRATOR_BASE_URL, "Public orchestrator base URL",
                    "Derselbe Orchestrator, wie ein Browser ihn erreicht - Basis des QR-Deep-Links, z.B. http://localhost:8080"),
            property(PEER_AUTH_ISSUER, "Peer-auth issuer",
                    "iss-Claim der signierten Peer-Auth-Assertion an den Orchestrator"),
            property(PEER_AUTH_AUDIENCE, "Peer-auth audience",
                    "aud-Claim derselben Assertion"),
            secret(PEER_AUTH_SIGNING_KEY, "Peer-auth signing key",
                    "Wird beim Speichern erzeugt. Leeren und speichern erzeugt einen neuen (Rotation).")
    );

    private static ProviderConfigProperty secret(String name, String label, String helpText) {
        ProviderConfigProperty property = property(name, label, helpText);
        property.setType(ProviderConfigProperty.PASSWORD);
        property.setSecret(true);
        return property;
    }

    private static ProviderConfigProperty property(String name, String label, String helpText) {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(name);
        property.setLabel(label);
        property.setHelpText(helpText);
        property.setType(ProviderConfigProperty.STRING_TYPE);
        return property;
    }

    /** Fuer die Komponente selbst, die ihr eigenes {@link ComponentModel} schon in der Hand hat. */
    public static OrchestratorSettings from(ComponentModel model) {
        return new OrchestratorSettings(
                required(model, ORCHESTRATOR_BASE_URL),
                required(model, PUBLIC_ORCHESTRATOR_BASE_URL),
                required(model, PEER_AUTH_ISSUER),
                required(model, PEER_AUTH_AUDIENCE),
                signingKey(model)
        );
    }

    /**
     * Legt den Signaturschluessel an, falls er fehlt. Aufgerufen aus
     * {@code OrchestratorStorageProviderFactory.validateConfiguration}, also beim Anlegen oder Aendern
     * der Komponente; Keycloak speichert das ergaenzte Modell mit. Nicht beim ersten Lesen: Keycloak
     * instanziiert den Provider bei jeder User-Anlage, und parallele Anlagen scheitern dann an der
     * optimistischen Sperre auf COMPONENT_CONFIG.
     */
    public static void ensureSigningKey(ComponentModel model) {
        String stored = model.getConfig().getFirst(PEER_AUTH_SIGNING_KEY);
        // Die Maske kommt an, wenn jemand die Config aus der Admin-API kopiert und damit eine neue
        // Komponente anlegt. Beim Aendern ersetzt Keycloak die Maske vorher selbst durch den alten Wert.
        if (stored == null || stored.isBlank() || ComponentRepresentation.SECRET_VALUE.equals(stored)) {
            model.getConfig().putSingle(PEER_AUTH_SIGNING_KEY, generateSigningKey().toJSONString());
        }
    }

    /** Liest nur; angelegt wird der Schluessel in {@link #ensureSigningKey}. */
    private static ECKey signingKey(ComponentModel model) {
        String stored = model.getConfig().getFirst(PEER_AUTH_SIGNING_KEY);
        if (stored == null || stored.isBlank()) {
            throw new IllegalStateException("Komponente '" + model.getName()
                    + "' hat keinen Peer-Auth-Signaturschluessel - einmal in der Admin-Console speichern,"
                    + " dann legt validateConfiguration ihn an");
        }
        try {
            return ECKey.parse(stored);
        } catch (ParseException e) {
            throw new IllegalStateException("Peer-Auth-Signaturschluessel der Komponente ist unlesbar", e);
        }
    }

    private static ECKey generateSigningKey() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID("kc-ext-" + System.currentTimeMillis())
                    .algorithm(JWSAlgorithm.ES256)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate peer-auth signing key", e);
        }
    }

    /**
     * Fuer Bausteine ohne eigenes Komponentenmodell: Sie finden die Komponente ueber ihre providerId,
     * weil die Komponenten-Id je Umgebung variiert.
     */
    public static OrchestratorSettings of(KeycloakSession session) {
        RealmModel realm = session.getContext().getRealm();
        return from(OrchestratorComponent.in(realm)
                .orElseThrow(() -> new IllegalStateException(
                        "User-Storage-Komponente '" + OrchestratorComponent.PROVIDER_ID
                                + "' fehlt im Realm '" + realm.getName()
                                + "' - ohne sie hat diese Extension keine Konfiguration")));
    }

    /** Ein Client mit genau diesen Einstellungen. */
    public OrchestratorClient newClient() {
        return new OrchestratorClient(orchestratorBaseUrl, peerAuthIssuer, peerAuthAudience, peerAuthSigningKey);
    }

    private static String required(ComponentModel model, String key) {
        String value = model.getConfig().getFirst(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Config-Property '" + key + "' der Komponente '" + model.getName() + "' ist nicht gesetzt");
        }
        return value;
    }
}
