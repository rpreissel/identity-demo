# Idee: Keycloaks native Verfahren als Tools des Orchestrators

Status: **Konzept, nicht umgesetzt** (Stand 2026-10-04). Das Dokument beschreibt, wie Keycloaks
eigene Verfahren (Passkey, OTP, Passwortformular) zu gewöhnlichen Tools des Orchestrators werden:
Der Orchestrator führt, Keycloak ist für ihn ein Fremdsystem, das einen Faktor hält und prüft.
Die Aussagen über Keycloaks Innenleben sind nicht gegen 26.6 erprobt; das ist Aufgabe des Spikes
(Abschnitt 7).

---

## 1) Ausgangslage

Im Web-Kanal gibt es heute zwei Arten von Verfahren
([ADR-8](../adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md)):

- **Tools des Orchestrators.** Die Journey bietet sie an, die Keycloak-Erweiterung zeigt sie über
  einen `WebToolRenderer`.
- **Native Schritte von Keycloak.** Keycloak führt sie in seinem eigenen Ablauf aus und meldet den
  Nachweis danach (`OrchestratorUpdateAuthenticator`, `NativeAuthenticatorRegistry`). Das Passwort
  ist ein Zwitter: Formular bei Keycloak, Geheimnis beim Orchestrator
  (`OrchestratorStorageProvider`, `MgmtPasswordController`).

Die nativen Schritte laufen an den Regeln des Kontos vorbei:

- Sie tragen kein `enrolledUnderAcr` und gelten ungekappt.
- Ein natives Verfahren steht nicht in `activeMethods`. Es fehlt in der Verwaltung, in der
  Aussperr-Prüfung und beim Löschen des Kontos.
- Das Passwort lässt sich über Keycloak setzen, ohne Journey, Floor und Frische
  ([Verfahren ändern](verfahren-aendern.md)).
- Fehlversuche zählen an zwei Stellen: in Keycloaks Brute-Force-Schutz und in der Kontosperre.

## 2) Grundidee

**Jedes Verfahren ist ein Tool.** Auch für Passkey und OTP gibt es ein Modul mit Enroll- und
Auth-Tool, Katalogeintrag und Journey. Das Modul hält kein Geheimnis, nur einen Verweis auf das
Credential in Keycloak (`EnrollmentRef`). So verweist das KOBIL-Modul heute auf das Gerät bei
KOBIL.

**Keycloak ist ein Fremdsystem.** Es hält den Faktor, prüft ihn und bezeugt das Ergebnis. Was
daraus für das Konto folgt, entscheidet der Orchestrator.

**Die Anzeige im Web delegiert.** Der `WebToolRenderer` eines solchen Tools zeichnet kein eigenes
Formular. Er übergibt an Keycloaks nativen Authenticator, fängt dessen Ergebnis ab und meldet es
dem Tool.

**Die App bietet diese Tools nicht an**, oder nur die, die ohne Keycloak auskommen. Die
Verfügbarkeit je Kanal gibt es schon (`ToolAvailabilityService`).

## 3) Wo das technisch ansetzen würde

**Im Orchestrator**

- Ein Modul je Verfahren nach dem Muster von KOBIL, mit eigener Tabelle für den Verweis auf das
  Credential.
- Der Controller des Tools nimmt den Abschluss nur von Keycloak an (`@BindingKey(keycloakOnly =
  true)`, wie heute `MgmtPasswordController`) und liefert ein gewöhnliches `ToolOutcome`.
- Entfernt jemand das Verfahren, löscht das Modul das Credential in Keycloak
  (`EnrollmentCleanup`, `KeycloakAdminClient`). Ein liegengebliebenes Credential ist unkritisch:
  Die Journey bietet ein nicht mehr aktives Verfahren nie an.

**In der Keycloak-Erweiterung**

- Der `WebToolRenderer` holt den nativen Authenticator über dessen Factory und ruft ihn mit einem
  umhüllten Kontext auf. Der native Authenticator meldet Erfolg sonst direkt an Keycloaks Ablauf;
  die Hülle fängt Erfolg und Misserfolg ab und meldet sie zuerst dem Orchestrator.
- Beim Einrichten gilt dasselbe für die native Required Action, aufgerufen aus
  `OrchestratorManageMethodsRequiredAction`.
- `OrchestratorNextDispatch` bleibt, wie es ist: Für ihn ist es ein Tool wie jedes andere.

**Was entfiele**

- `NativeAuthenticatorRegistry` und die ungekappten nativen Faktoren.
- `OrchestratorUpdateAuthenticator`.
- Der zustandslose Passwortweg (`OrchestratorStorageProvider.isValid`/`updateCredential`,
  `MgmtPasswordController`). Das Passwort bleibt ein Tool mit dem Geheimnis im Orchestrator; im
  Web kann sein Renderer Keycloaks Passwortseite als Vorlage nutzen.

## 4) Je Verfahren

| Verfahren | Geheimnis | Web | App |
|---|---|---|---|
| Passwort | Orchestrator | Tool, Anzeige wie Keycloaks Passwortseite | Tool wie heute |
| OTP | Keycloak | Tool, delegiert an Keycloaks OTP-Schritt | nicht angeboten |
| Passkey | Keycloak | Tool, delegiert an Keycloaks WebAuthn-Schritt | nicht angeboten |

- **OTP in beiden Kanälen** bleibt ein eigenes Tool mit dem Geheimnis im Orchestrator
  ([Beispiel neues Verfahren](../15-beispiel-neues-verfahren.md)). Die Delegation lohnt nur, wenn
  das Web allein genügt.
- **Passkey ohne Benutzernamen** braucht ein Lookup-Tool (`auth-passkey-lookup`), das das Konto
  aus dem Passkey auflöst.

## 5) Was sich an bestehendem Verhalten ändern würde

- ADR-8 dreht sich für die nativen Verfahren um: Welches Verfahren drankommt, entscheidet die
  Journey, nicht mehr Keycloaks konfigurierter Ablauf mit Conditional-LoA. Es bräuchte eine neue
  ADR, die ADR-8 in diesem Punkt ablöst.
- Für alle Verfahren gelten dieselben Regeln: `enrolledUnderAcr`, Floor, Frische, Kontosperre,
  `activeMethods`, Aussperr-Prüfung, Kontolöschung.
- Fehlversuche zählt nur noch der Orchestrator. Keycloaks Brute-Force-Schutz sieht die delegierten
  Schritte weiterhin, entscheidet aber nicht mehr über die Sperre.

## 6) Offene Fragen und Risiken

- **Lässt sich ein nativer Authenticator sauber umhüllen?** Das ist die tragende Annahme. Die Hülle
  nutzt die Authenticator-SPI, hängt aber am Verhalten der nativen Klassen (Auth-Notes,
  Action-URLs der laufenden Execution) und ist bei jedem Keycloak-Upgrade zu prüfen.
- **Einrichten braucht einen Keycloak-Nutzer.** Ein Passkey lässt sich erst registrieren, wenn das
  Konto existiert, also nicht mitten in einer Registrierung ohne Konto.
- **Wo liegt das Credential?** Die Nutzer-Federation arbeitet ohne Import
  ([ADR-38](../adr/ADR-038-keycloak-liest-konten.md)). Zu klären ist, ob Keycloak Credentials für
  solche Nutzer dauerhaft und stabil unter derselben Nutzer-Id hält.
- **Zwei Zeugen für das Löschen.** Scheitert das Löschen in Keycloak, bleibt ein Credential ohne
  Verfahren zurück. Es braucht einen Abgleich oder die Zusage, dass das genügt.
- **Wiederherstellen des Kanals.** `OrchestratorResumeAuthenticator` und `restoreData` tragen
  heute native Nachweise mit. Ohne native Schritte vereinfacht sich das; der Umfang ist zu prüfen.

## 7) Nächste Schritte

Ein Spike mit OTP als kleinstem Fall, bevor Passkey drankommt:

- Ein Tool `auth-otp`, dessen Renderer Keycloaks OTP-Schritt mit umhülltem Kontext aufruft.
- Erfolg, Fehlversuch und Abbruch erreichen den Orchestrator, bevor Keycloaks Ablauf weitergeht.
- Ein Reload und der Zurück-Knopf des Browsers brechen den Schritt nicht.

Trägt die Hülle, folgt der Rest dem Muster von KOBIL. Trägt sie nicht, bleibt es bei ADR-8, und
die nativen Verfahren melden dem Orchestrator nur ihr Einrichten und Entfernen.
