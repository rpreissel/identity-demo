# Offene Befunde

Ein **Befund** ist ein Problem oder eine Schwäche, die eine Bewertung des Projekts gefunden hat.
Diese Seite sammelt alle Befunde, die noch offen sind, an einer Stelle. Sie stammen aus drei
Bewertungen:

- aus der dritten Bewertung (2026-09-27),
- aus der vierten Bewertung (2026-09-29),
- aus dem Sicherheitsaudit (2026-10-03).

Die Befunde des Audits vom 2026-10-09 stehen noch in einer eigenen Datei,
[review-2026-10-09-audit.md](review-2026-10-09-audit.md). Die Korrekturen aus ihrem Abschnitt 5
sind hier eingearbeitet.

Die Liste wurde am 2026-10-04 mit dem Code abgeglichen. Was erledigt ist, steht hier nicht mehr. Es
ist in der Git-Historie und in den geschlossenen Issues nachzulesen (Epics `DPoP-demo-9ppv`,
`DPoP-demo-updm`, `DPoP-demo-164n`).

Am Ende stehen in Abschnitt 8 die erkannten, bewusst zurückgestellten Verbesserungen. Sie sind keine
Befunde einer Bewertung. Es sind Entscheidungen über Architektur oder Infrastruktur, die noch
ausstehen.

So ist die Liste zu lesen:

- **Kürzel:** Jeder Befund behält das Kürzel aus der Bewertung, in der er gefunden wurde. Doku,
  Issues und der [Lesepfad Sicherheit](16-lesepfad-sicherheit.md) verweisen mit diesen Kürzeln
  darauf. `S-`, `K-`, `A-` und `Q-` ohne Zusatz stammen aus der vierten Bewertung. Mit dem Zusatz
  „(3.)“ stammen sie aus der dritten Bewertung. `SA-` stammt aus dem Sicherheitsaudit.
- **Schwere:** wie in den Bewertungen.
  - *mittel:* Eine Sicherheitszusage gilt nicht, und ein Missbrauch ist realistisch.
  - *niedrig:* Die Wirkung ist begrenzt.
  - *Hinweis:* eine Frage der Sorgfalt, kein akutes Problem.
  - *bewusst:* Eine Entscheidung nimmt das Problem in Kauf. Es steht hier als benanntes
    Restrisiko.
- **Issue:** Mit `bd show <id>` sehen Sie das zugehörige Issue im Issue-Tracker. „–“ heißt: Es gibt
  noch kein Issue.

Der Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md): Der
Backend-Kern soll produktionsreif sein. Jede Sicherheitszusage muss gelten, ohne dass sie
stillschweigend etwas von der Umgebung voraussetzt.

---

## 1. Sicherheit im Kern

- **S-2 (niedrig) `ident-nect`: `retry` ohne Budget.**
  Das Tool `ident-nect` identifiziert eine Person über den Anbieter Nect. `IdentNectToolHandler.patch`
  legt bei jedem `retry` einen neuen Fall an, ohne die Versuche zu zählen. Vorschlag: ein `RateLimit`
  des Moduls (etwa 3 Versuche je ToolSession und 10 Minuten, danach `429`). Die Fälle in
  `nect.ident_case` gehören dem simulierten Nect und unterliegen nicht unseren Aufbewahrungsregeln
  ([08-projektrahmen.md](08-projektrahmen.md), M17). Issue: –
- **S-3 (3.) (niedrig) Die DPoP-Replay-Tabelle wächst vor Kanal- und Drosselprüfung.** Jeder
  DPoP-Proof (der signierte Beleg, den die App mit jeder Anfrage schickt) wird in einer Tabelle
  gespeichert, damit er sich nicht wiederverwenden lässt. Jeder syntaktisch gültige Proof schreibt
  dort eine Zeile, noch bevor der Orchestrator prüft, ob der Kanal existiert und ob die Drosselung die Anfrage ablehnt. Neue
  Schlüssel kosten einen Angreifer nichts. `DPoP-demo-9ppv.1`
- **A-4 (niedrig) „`auth-invite` nur im Web-Kanal“ ist eine umschaltbare Voreinstellung.** Dass das
  Tool `auth-invite` (Vorgangszugang mit Einmalkennwort) nur im Web-Kanal läuft, ist keine feste
  Eigenschaft des Tools, sondern eine Einstellung. Die dritte Prüfstelle,
  `JourneyActionExecutor.acceptInvitation`, prüft mit `check` und antwortet deshalb mit `500` statt
  `409`. Der Inhaber muss entscheiden: Entweder wird die Bindung an den Kanal eine Eigenschaft des
  Tools (`ChannelType` nach `tool_api`). Oder es bleibt bei der Voreinstellung, und die dritte
  Prüfstelle meldet `invalidState`. Issue: –
- **A-5 (niedrig) Ein erfolgreicher Vorgangszugang setzt den Personenzähler nicht zurück.**
  `ToolJourneyService.chargeRateLimits` setzt bei `Completed.Authenticated` nur den Zähler des
  Kontos zurück. Beispiel: Vier Fehlversuche, dann ein Erfolg, dann ein Fehlversuch sperren die
  Person für 15 Minuten. Das gilt auch für `ident-fsc`. Lösung: Das Ergebnis eines Tools muss die
  Person nennen können. Das ist eine Änderung am Vertrag zwischen Orchestrator und Tools und
  verlangt eine Entscheidung des Inhabers. Issue: –
- **Lookup-Orakel (niedrig).** Ein Lookup-Tool sucht das Konto anhand der Eingabe, etwa einer
  E-Mail-Adresse. Ein Orakel ist ein Verhalten, aus dem ein Angreifer etwas ablesen kann, das
  geheim bleiben soll. Hier verraten die Demo-TAN und, mit einem echten Anbieter, die Dauer des
  Versands, ob zu einer Adresse ein Konto existiert. `DPoP-demo-36xz`
- **I-23 (niedrig) Kanal-Lebensdauer und Keycloak-Sitzung.** Der Web-Kanal steht schon auf
  `AUTHENTICATED`, bevor Keycloak die Sitzung anlegt. Die Meldung einer Abmeldung und `flow-end`
  werden nur nach bestem Bemühen („best effort“) zugestellt. `DPoP-demo-oe06`
- **I-14 (niedrig) Gerätelink ohne Fremdschlüssel.** Die Verknüpfung eines Geräts mit einem Konto
  ist in der Datenbank nicht per Fremdschlüssel abgesichert. `DPoP-demo-hwc6`
- **Test (niedrig) Zwei gleichzeitige `PATCH` auf dieselbe ToolSession** sind über `@Version`
  geschützt, aber nicht getestet. `DPoP-demo-df48`
- **A-16 (3.) (Hinweis) I-10 gilt per Regel nur für den Resolver**, nicht für den
  `IdentityMatchingService` dahinter. `DPoP-demo-9ppv.23`
- **Hinweis: Beim Einrichten in der Verfahrensverwaltung prüft der Orchestrator `loa2` beim
  Abschluss nicht erneut** (`ManageAuthMethodsStrategy`, Zustand `Enrolling`). Veraltet der
  Nachweis in der Zwischenzeit, wird das Verfahren mit einem niedrigeren Niveau eingetragen, nie mit
  einem höheren. Es besteht kein Handlungsbedarf. Issue: –
- **Hinweis: Der Freischaltcode liegt im simulierten Personenverzeichnis als Hash ohne Salt.** Der
  Port-Vertrag, also die Beschreibung der Schnittstelle zum Fremdsystem, sollte nennen, was ein
  echtes System hier leisten muss. `DPoP-demo-4xnr`

## 2. Keycloak-Erweiterung und -Anbindung

- **Fehlerpfade der Required Action (niedrig)** zeigen die allgemeine Fehlerseite von Keycloak:
  `OrchestratorManageMethodsRequiredAction` ruft `context.failure()`. Der Authenticator tut das
  nicht mehr. `DPoP-demo-rdns`
- **K-6 (Hinweis) Antwort-JWKS mit Nimbus-Voreinstellungen.** Die Keycloak-Erweiterung prüft die
  Antworten des Orchestrators mit dessen öffentlichen Schlüsseln (JWKS). `OrchestratorResponseVerifier`
  lädt diese mit `JWKSourceBuilder.create(…).retrying(true)`. Das bedeutet 500 ms Zeitlimit und kein
  `outageTolerant`. Vorschlag: ein eigener `ResourceRetriever` mit 3 s/10 s und Größenlimit, dazu
  `outageTolerant`. Issue: –
- **K-8 (Hinweis, bewusst) Jeder GET auf die Action-URL wird zur Eingabe für das Tool**, auch
  `orchestrator_back` und `orchestrator_abandon`. Die Action-URL ist die Adresse, an die das
  Anmeldeformular in Keycloak geschickt wird. Ein Angreifer braucht dafür den Aktionscode und das
  Cookie. Möglichkeit: das nur für Tools mit `activationFields` zulassen. Issue: –
- **K-8 (3.) (Hinweis) QR-Status-Endpunkt ohne Mindestintervall.** Der Endpunkt lässt sich beliebig
  oft abfragen. `DPoP-demo-9ppv.10`
- **K-14 (3.) (Hinweis) Peer-Auth-Fenster 300 s im Profil `keycloak`** statt nur in der Variante
  `host`. Peer-Auth heißt, dass sich Keycloak und Orchestrator mit signierten Nachrichten gegenseitig
  ausweisen. `DPoP-demo-9ppv.13`
- **Hinweis: Die Kanal-Id des Web-Kanals ist aus der Tab-Id abgeleitet** und damit vorhersagbar. Für
  einen Zugriff braucht man trotzdem eine signierte Assertion von Keycloak mit passendem
  `channel_binding`. `DPoP-demo-gxis`
- **SA-21 (Hinweis) Redirect-URIs mit Platzhalter** (`…/*` in `application-keycloak.yml`). Eine
  Änderung betrifft das Realm-Setup und erzwingt einen Neuaufbau. Vorher müssen die Pfade der
  Single-Page-App geklärt sein. `DPoP-demo-164n.21`
- **`loa3` im Web-Realm (offen, Entscheidung).** `DPoP-demo-wzcm`

## 3. Architektur

- **A-6 (Hinweis) Die Erweiterung liest die Uhr selbst** (`PeerAuthAssertionSigner`,
  `OrchestratorResponseVerifier`, `OrchestratorSettings`, dazu die Caches in `OrchestratorTexts` und
  `OrchestratorToolCatalog` und `OrchestratorNotes`). Die Zeitregeln der Peer-Auth lassen sich
  dort nur mit echten Wartezeiten testen. Vorschlag: `Clock` als Konstruktorparameter. Issue: –
- **A-7 (Hinweis) Drei Formen für „wer“ in `tool_api`** (`Subject`, `Attempted`, `AuthSubject`).
  Fachlich sind sie verschieden; die Einladung trägt inzwischen ihre `InvitationId`. Dazu kommen
  `KcSubject` in der Erweiterung und das Spaltenpaar `accountId`/`invitation` in der Persistenz.
  Issue: –
- **A-8 (Hinweis) Die Prüfung von Assertion und `channel_binding` ist in den kc-Controllern
  wiederholt** (`KeycloakAccountLookupController`, `KeycloakInvitationLookupController`,
  `KeycloakSignOutController`). Vorschlag: ein gemeinsamer Helfer am `PeerAuthValidator`. Issue: –
- **Aus der dritten Bewertung:**
  - A-7: Modulabhängigkeiten per Test prüfen (`DPoP-demo-9ppv.15`).
  - A-8: tote Enum-Werte und CHECKs für Zustände (`9ppv.16`).
  - A-11: gemeinsame Wurzel der REGISTER-Zustände (`9ppv.19`).
  - A-13: `DemoStepReason` aus dem Fachkern entfernen (`9ppv.21`).
  - A-14: nur `InvalidInputException` wird zu `400` (`9ppv.22`).
  - K-5: ArchUnit-Regel „keine Transaktion um Keycloak-Aufrufe“ (`9ppv.7`).

## 4. Codequalität und Tests

- **Q-4 (niedrig) `!!` auf dem gerade geprüften Feld** in `AuthInviteLookupFlow` und
  `AuthPasswordLookupFlow`. Insgesamt gibt es 21 `!!`. Zusammen mit Q-8 (3.) in `DPoP-demo-9ppv.27`.
- **Q-5 (niedrig) Testhelfer mehrfach definiert**, und `IntegrationTestSupport` ist groß. Zusammen
  mit Q-13 (3.) in `DPoP-demo-9ppv.32`.
- **Q-6 / K-10 (niedrig) Testlücken der Erweiterung.** Die Verteilung ist entdoppelt: die
  Einordnung des nächsten Schritts in `OrchestratorNextDispatch`, die Tool-Schritte in `ToolSteps`.
  Ohne Test sind weiter: die Required Action, der Resume-Authenticator, `WebFormRenderer` und die
  meisten Renderer-Factories. `DPoP-demo-9ppv.12`
- **Q-7 (Hinweis) `ident_eid` ist vom Kover-Tor ausgenommen**, enthält aber Kernlogik
  (`IdentEidFlow`). Das Kover-Tor prüft die Testabdeckung. Vorschlag: den Ausschluss auf
  `simulation.*` beschränken. Issue: –
- **Q-8 / Q-11 (3.) (Hinweis) Umlaute in Text-Vorlagen gemischt.** `DPoP-demo-9ppv.30`
- **Q-9 (Hinweis) `e2e-keycloak` läuft nicht in der CI.** Lokal gegen compose lief es am 2026-10-04
  fehlerfrei. Ein nächtlicher Job mit `podman compose` wäre der Weg. Issue: –
- **Q-10 (Hinweis) `RegisterStrategy.transition` mit 71 Zeilen**, ein `when` über acht Zustände. Es
  besteht kein Handlungsbedarf.
- **Aus der dritten Bewertung:**
  - Q-5: `auth_email` löst das Konto im Controller auf (`DPoP-demo-9ppv.24`).
  - Q-6: QR-Controller mit OpenAPI-Beispielen (`9ppv.25`).
  - Q-7: ein Weg zum Paar aus Journey und Kanal (`9ppv.26`).
  - Q-10: `else` bei sealed-Subjekten (`9ppv.29`).
  - Q-12: tote Deklarationen (`9ppv.31`).
  - Q-14: lange KDocs (`9ppv.33`).
  - Q-16: `relaxed`-Mocks (`9ppv.34`).
  - Q-17: `allWarningsAsErrors` (`9ppv.35`).

## 5. Umgebung und Betrieb

- **SA-25 (Hinweis) Keine NetworkPolicy auf OpenShift.** Die Management-Ports sind aus anderen Pods
  erreichbar. `DPoP-demo-164n.25`
- **S-9 (3.) (Hinweis) Keycloak-Bootstrap-Admin nicht im Startcheck**, Hinweis auf `DEMO_MODE` in der
  Doku. `DPoP-demo-9ppv.3`. Außerdem gilt ein fehlendes `demo.mode` als Demomodus
  (`DPoP-demo-davx`).
- **S-12 (3.) (Hinweis) Laufzeit-Image in compose nicht auf eine feste Version gepinnt.**
  `DPoP-demo-9ppv.5`
- **Umgebung:** Dazu gehören diese offenen Punkte:
  - TLS zwischen Keycloak und Orchestrator und die Proxy-Header (`DPoP-demo-ai4x`),
  - Keycloak mit `start --optimized` (`DPoP-demo-9msv`),
  - das Admin-Geheimnis auf OpenShift (`DPoP-demo-x25a`),
  - PostgreSQL (`DPoP-demo-pi55`),
  - eine gemeinsame Sperre für geplante Aufgaben (`DPoP-demo-g7np`),
  - Backup und Restore (`DPoP-demo-prnl`),
  - im Frontend: CSP, Tokens im Browser, `state`/`nonce` (`DPoP-demo-dm2j`).

## 6. Bewusst in Kauf genommen

- **SA-27 (Rest) Die Personensperre prüft vor dem Versuch und zählt danach.** Die Kontosperre bucht
  seit dem Audit vom 2026-10-09 vorab (AU-10). Für Personen bleibt es dabei: Freischaltcode und
  Einmalkennwort sind zu lang zum Raten ([07-betrieb.md](07-betrieb.md) Abschnitt 4).
- **S-7 `auth-invite` und `ident-fsc`: Eine unbekannte Nummer kostet nichts, eine bekannte
  antwortet messbar anders.** Ein Angreifer kann also erkennen, ob eine Nummer existiert. Das
  Kennwort selbst lässt sich aber nicht erraten. Zusammen mit `DPoP-demo-36xz`.
- **S-8 Keycloaks Action-URL samt Aktionscode wird am Nect-Fall gespeichert und an das Fremdsystem
  geschickt** (ADR-47). Möglichkeit: eine eigene Rücksprungadresse am Orchestrator.
- **Das `acr` im Token altert nicht** (SA-5). Das `acr` ist das Niveau, das im Token steht. Es
  beschreibt wie bei Keycloak üblich die Anmeldung ([04-orchestrierung.md](04-orchestrierung.md)
  Abschnitt 4). Anwendungen prüfen `acr` und, wenn sie einen frischen Nachweis brauchen, zusätzlich
  `auth_time` (`DPoP-demo-mea0`).
- Weitere bewusst in Kauf genommene Punkte führt der [Lesepfad Sicherheit](16-lesepfad-sicherheit.md)
  in seinem Abschnitt 15 auf: DPoP ohne Nonce, Tokens nicht an DPoP gebunden, KOBIL-PIN für den Server lesbar.

## 7. Offene Entscheidungen des Inhabers

- **Passwortwechsel:** Die Verfahrensverwaltung bietet `enroll-password` bei aktivem Passwort als
  „ersetzen“ an ([verfahren/password.md](verfahren/password.md)). Offen sind noch:
  - der Anstoß durch den Admin per `execute-actions-email` (`DPoP-demo-164n.27`),
  - die Account-Konsole für föderierte Nutzer, also Nutzer, die Keycloak beim Orchestrator
    nachliest (`DPoP-demo-164n.28`).
- **A-4** Bindung eines Tools an einen Kanal und **A-5** Person am Anmeldeergebnis (siehe
  Abschnitt 1).
- Weitere offene Entscheidungen:
  - **Verschlüsselung personenbezogener Spalten** (`DPoP-demo-bo1w`),
  - **Aufwerten nach erneuter Identifizierung** (`DPoP-demo-wyp3`),
  - **`loa3` im Web-Realm** (`DPoP-demo-wzcm`).
- **Echte Fremdsysteme:** Nect (`DPoP-demo-v033`, `DPoP-demo-z90h`).

## 8. Erkannte, bewusst zurückgestellte Verbesserungen

Diese bekannten Punkte sind bewusst **nicht** vollständig umgesetzt. Jeder davon verlangt eine
Entscheidung über Architektur oder Infrastruktur. Keiner lässt sich mit einer Korrektur an einer
einzigen Stelle erledigen.

- **Skalierung von `orchestrator.dpop_proof_replay`** (siehe auch [09-dpop.md](09-dpop.md)
  Abschnitt 2): In dieser Tabelle merkt sich der Orchestrator benutzte DPoP-Proofs. Der Schlüssel ist
  seit ADR-14 ein SHA-256-Hash mit fester Länge. Offen ist, ob man die Tabelle nach Zeit
  partitioniert oder durch einen eigenen, dauerhaften Schlüssel-Wert-Speicher ersetzt. Das ist eine
  Entscheidung für die Produktivumgebung.
- **Lebenszyklus eines Kontos und Zusammenführen von Konten:** `Account` hat weder einen Status noch
  ein Feld `merged_into`. ADR-11 lehnt einen Konflikt um eine `person_id` bewusst ab, statt die
  Konten zusammenzuführen. Über die angestrebte Lebensdauer des Systems wird ein Zusammenführen aber
  zwangsläufig nötig. Ohne `merged_into` gibt es dann keinen Weg dorthin ohne Datenverlust.
- **Sehr viele Konten** (Größenordnung 10 Millionen; `account.claim` hätte dann 20 bis 80 Millionen
  Zeilen). Die Demo erreicht das nie. Für den Fall, dass das Modell so groß wird, gilt:
  - Die häufigen Abfragen lesen weiterhin gezielt einzelne Zeilen über schmale, indizierte Spalten,
    nie über Paare aus Attribut und Wert. Das sind `account` über den Primärschlüssel,
    `account.anchor` über `(attribute_type, normalized_value)` und
    `orchestrator.device_account_link` über `binding_key_ref`.
  - Gesucht wird nur über normalisierte Werte (`normalizeAnchorValue`). `account.claim` braucht
    deshalb keinen Index für die Suche vom Wert zum Konto.
  - Bestehende Daten stellt man in wiederholbaren Portionen um, nicht in einer einzigen
    Transaktion.
  - Keycloak liest ein Konto bei Bedarf einzeln, über den Primärschlüssel oder den E-Mail-Anker
    ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)). Einen Abgleich aller Konten gibt es nicht.

  Das Modell der Claims dahinter beschreibt das [Domänenmodell](02-domaenenmodell.md) in
  Abschnitt 6. Was über das Datenmodell hinaus bei 20 Millionen Konten und 1 Million
  Anmeldungen am Tag zu ändern wäre, steht in
  [14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitt 7.

Alle drei Punkte verdienen eine eigene, sorgfältig geplante Überarbeitung. Erst danach wird über
den Entwurf entschieden.
