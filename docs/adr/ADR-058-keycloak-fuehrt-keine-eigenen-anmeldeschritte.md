# ADR-58: Keycloak führt keine eigenen Anmeldeschritte mehr

**Status:** entschieden 2026-10-09 (Spike `DPoP-demo-i9ni`), umgesetzt 2026-10-09 (Issue
`DPoP-demo-0ntu`).
Löst [ADR-8](ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md) und
[ADR-42](ADR-042-loa1-anmeldung-umschalten.md) ab.

**Worum es geht.** Auf der Website führt [Keycloak](../glossar/glossar.md) die Anmeldung. Bisher
gab es dort zwei Arten von Schritten ([ADR-8](ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md)):

- die [Tools](../glossar/glossar.md) des Orchestrators, die die
  [Journey](../glossar/glossar.md) anbietet und auswertet;
- Keycloaks eigene, „native“ Schritte, vor allem sein Passwortformular. Keycloak führt sie in seinem
  eigenen Ablauf aus und meldet den Nachweis danach an den Orchestrator.

Für die nativen Schritte gelten viele Regeln des Kontos nicht oder nur, weil eigener Code sie
nachbildet. Die Frage war, ob man die nativen Schritte behält, sie zu Tools macht oder sie
abschafft.

**Entscheidung.** Im Web-Kanal ist Keycloak nur noch die Fassade nach außen. Er spricht OpenID
Connect mit der Website, hält die Sitzung, stellt die Tokens aus und zeigt die Seiten über das
Login-Theme. Jeden Anmeldeschritt führt der Orchestrator als Tool aus, über
`OrchestratorAuthenticator`. Im Einzelnen:

- **Keycloaks Passwortformular entfällt** und damit der Schalter zwischen Formular und
  Verfahrensauswahl auf `loa1` (ADR-42). Wer sich mit Passwort anmeldet, nutzt das Tool
  `auth-password-lookup` bzw. `auth-password`.
- **Keycloak speichert und prüft keine Credentials.** Die Nutzer-Federation liest nur noch Konten
  ([ADR-38](ADR-038-keycloak-liest-konten.md)). Der zustandslose Weg für das Passwort entfällt
  (`OrchestratorStorageProvider` als `CredentialInputValidator`, `MgmtPasswordController`).
- **Native Verfahren werden nicht als Tools umhüllt.** Braucht das Projekt ein Verfahren, das
  Keycloak mitbringt, etwa OTP oder Passkey, wird es ein eigenes Tool mit eigenem Geheimnis im
  Orchestrator. Für OTP beschreibt Kapitel 15 das schon (`auth-totp`). Für den Passkey klärt ein
  eigener Spike, ob das Tool eine Bibliothek wie webauthn4j nutzt.

## Warum

**Ein Weg, eine Stelle für die Regeln.** Solange es native Schritte gibt, laufen Anmeldungen auf
zwei Wegen. Auf dem nativen Weg fehlen:

- die Obergrenze `enrolledUnderAcr`,
- die Verfahrensauswahl, das Angebot zur Geräteverknüpfung und der Weg zum Registrieren,
- ein einziger Zähler für Fehlversuche. Keycloaks Brute-Force-Schutz zählt neben der Kontosperre
  des Orchestrators mit.

Was dort doch gilt, etwa die Kontosperre beim Passwort, bildet eigener Code nach
(`KeycloakToolCalls`, `NativeAuthenticatorRegistry`, `OrchestratorUpdateAuthenticator`). Ohne
native Schritte entfällt dieser Code. Die Regeln stehen dann nur noch in der Journey.

**Das Passwortformular bringt fachlich nichts mehr.** Das Geheimnis liegt schon beim Orchestrator.
Keycloak trägt nur die Seite bei, und die gibt es als Tool-Seite. Der einzige verbliebene Grund war
der Vergleich „Keycloak klassisch gegen Orchestrator“ in der Demo (ADR-42). Diesen Vergleich braucht
die Demo nicht.

**Umhüllen lohnt sich nicht.** Der Spike `DPoP-demo-i9ni` hat Keycloaks OTP als Tool umhüllt und
gegen Keycloak 26.6.4 erprobt ([Konzept, Abschnitt 8](../ideen/native-verfahren-als-tools.md)). Die
Hülle selbst trägt: Einrichten und Anmelden laufen über Keycloaks eigene Schritte, und jedes
Ergebnis erreicht zuerst den Orchestrator. Aber Keycloaks Seiten hängen an Keycloaks Ablauf:

- Der Link zum Abtippen des Geheimnisses zeigt fest auf Keycloaks eigene Required Action.
- „Abbrechen“ beim Einrichten beendet die ganze Verwaltung der Verfahren, nicht nur das Tool.
- Die Anmeldeseite bietet auch Credentials an, die das Konto gar nicht mehr hat, und wählt sie
  sogar vor.
- Zurück und Abbrechen der Tool-Seiten fehlen.

Jedes native Verfahren bräuchte deshalb eigene Seiten im Theme, die an Keycloak-Interna hängen.
Dazu kommen zwei Speicherorte (das Credential in Keycloak, der Verweis im Orchestrator). Außerdem
laufen solche Verfahren nur im Web und lassen sich erst einrichten, wenn das Konto existiert. Ein
eigenes Tool hat keinen dieser Nachteile und gilt auch in der App.

## Erwogene Alternativen

- **Alles lassen, wie es ist (ADR-8).** Zwei Wege mit dem Nachbau der Regeln auf dem nativen Weg.
  Verworfen aus den Gründen oben.
- **Native Verfahren umhüllen** ([Konzept](../ideen/native-verfahren-als-tools.md)). Erprobt und
  verworfen, siehe oben.
- **Das Passwortformular als reinen Vergleichsmodus behalten.** Weniger Arbeit jetzt, aber der
  Sondercode und der globale, nicht atomare Schalter aus ADR-42 blieben. Verworfen, weil die Demo
  den Vergleich nicht braucht.

## Preis

- **Keycloak-Funktionen, die sein Formular voraussetzen, fallen weg.** „Passwort vergessen“
  (`reset-credentials`) und das Verknüpfen eines Kontos beim Identity-Brokering laufen nicht mehr
  über Keycloak. Braucht das Projekt sie, werden sie Journeys. Keycloak durfte das Passwort aber
  schon bisher nicht ändern.
- **Was das Formular von selbst konnte, muss die Tool-Seite können**: das Vorbelegen über
  `login_hint` und ein Markup, das Passwort-Manager erkennen.
- **Jeder erste Schritt öffnet einen Kanal beim Orchestrator.** Das sind ein paar Aufrufe mehr als
  beim nativen Formular. Ausfallsicherer war das Formular nicht, denn auch dort prüfte der
  Orchestrator das Passwort.
- **Neue Verfahren in Keycloak kommen nicht von selbst dazu.** Jedes Verfahren ist eigener Code im
  Orchestrator.

## Was mit der Umsetzung entfällt

Die vollständige Liste steht im Issue `DPoP-demo-0ntu`:

- In der Extension: `OrchestratorUpdateAuthenticator` und der Teil von
  `OrchestratorResumeAuthenticator`, der native Nachweise über `restoreData` überträgt. RestoreData
  selbst bleibt: Es trägt weiter die Nachweise eines früheren Durchlaufs.
  `OrchestratorStorageProvider` ist kein `CredentialInputValidator` und kein
  `CredentialInputUpdater` mehr.
- Im Realm: die Executions `auth-username-password-form` und `orchestrator-update-authenticator`
  im Subflow `orchestrator-loa-1`. Er hat jetzt nur die LoA-Bedingung und
  `orchestrator-authenticator`, wie `orchestrator-loa-2`. Dazu Keycloaks Brute-Force-Schutz und die
  Anmeldung mit E-Mail-Adresse im Passwortformular. Keycloak prüft kein Geheimnis mehr. Fehlversuche
  zählt allein die Kontosperre des Orchestrators
  ([ADR-44](ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).
- Im Orchestrator: `KeycloakLoa1Login`, `Loa1LoginSwitch` mit `KeycloakFeatureFlags.LOA1_PASSWORD`,
  die Admin- und Demo-Endpunkte `loa1-login`, das Feld `loa1Login` im Server-Status,
  `MgmtPasswordController`, `KeycloakToolCalls`, `NativeAuthenticatorRegistry` und `AmrEntry` im
  Aufruf von `upsertChannel`, mit ihm das Journey-Ereignis `EvidenceReported` und
  `JourneyService.applyEvidenceUpdate`. Das Zurücksetzen der Demo setzt keinen Schalter für `loa1` mehr.
- Im Frontend: die Einstellung „Erste Anmeldeseite der Website“ auf der Admin-Seite, die Wahl „So
  beginnt die Anmeldung“ in der Demo-Spalte der Website und die Kachel „Erste Anmeldeseite“ im
  Server-Status der Startseite.
- Im Vertrag fallen nur Routen unter `/kc` weg. Sie gehören nicht zum eingefrorenen Vertrag der App
  ([ADR-50](ADR-050-api-versionierung-umschlag-und-tool.md)).

**Realm direkt in V1 geändert.** Statt einer neuen Keycloak-Migration ist die Realm-Definition
`V1__realm` selbst geändert. Eine geänderte, schon angewendete Migration lässt den Orchestrator
beim nächsten Start das Realm löschen und neu aufbauen (`MigrationRunner`, nur im Demomodus;
sonst bricht der Start ab). Das ist hier vertretbar: Keycloak hält keine eigenen Daten, die
verloren gehen könnten. Die Nutzer liest er wieder über die Federation, beim nächsten Abgleich.
Verloren gehen nur offene Sitzungen.
