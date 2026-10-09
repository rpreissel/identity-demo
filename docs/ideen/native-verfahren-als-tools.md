# Idee: Keycloaks native Verfahren als Tools des Orchestrators

**Worum es geht.** Auf der Website führt [Keycloak](../glossar/glossar.md) die Anmeldung. Einige
Anmeldeverfahren bringt Keycloak selbst mit: den Passkey, das Einmalpasswort aus einer
Authenticator-App (OTP) und das Passwortformular. Diese „nativen“ Verfahren führt Keycloak in
seinem eigenen Ablauf aus, am Orchestrator vorbei. Alle anderen Verfahren sind
[Tools](../glossar/glossar.md) des Orchestrators. Ein Tool ist ein abgeschlossener Arbeitsschritt,
den der Orchestrator anbietet und auswertet. Die Idee: Auch die nativen Verfahren werden zu
gewöhnlichen Tools. Der Orchestrator entscheidet dann über alle Verfahren. Keycloak ist für ihn
nur noch ein Fremdsystem, das einen Faktor speichert und prüft.

**Warum das wichtig ist.** Für die nativen Verfahren gelten heute wichtige Regeln des Kontos nicht.
Dazu gehören die Obergrenze für das Sicherheitsniveau, die Prüfung gegen Aussperren und das
Löschen beim Löschen des Kontos. Abschnitt 1 beschreibt das genauer.

**Stand: verworfen** (2026-10-09). Ein Spike hat die Hülle gegen Keycloak 26.6.4 erprobt.
Sie trägt, aber Keycloaks eigene Seiten hängen an Keycloaks Ablauf (Abschnitt 8).
[ADR-58](../adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md) entscheidet deshalb:
Native Verfahren werden nicht umhüllt, und Keycloak führt keine eigenen Anmeldeschritte mehr.

---

## 1) Ausgangslage

Im Web-Kanal gibt es heute zwei Arten von Verfahren
([ADR-8](../adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md)):

- **Tools des Orchestrators.** Die [Journey](../glossar/glossar.md), also der geführte Ablauf im
  Orchestrator, bietet sie an. Die Keycloak-Erweiterung zeigt sie über einen `WebToolRenderer` an.
- **Native Schritte von Keycloak.** Keycloak führt sie in seinem eigenen Ablauf aus und meldet den
  Nachweis danach an den Orchestrator (`OrchestratorUpdateAuthenticator`,
  `NativeAuthenticatorRegistry`).

Das Passwort nimmt eine Sonderstellung zwischen beiden Arten ein: Das Formular zeigt Keycloak, das
Geheimnis liegt aber beim Orchestrator (`OrchestratorStorageProvider`, `MgmtPasswordController`).

Für die nativen Schritte gelten die Regeln des Kontos nicht:

- Sie tragen kein `enrolledUnderAcr`. Das ist das Niveau der Sitzung, in der ein Verfahren
  eingerichtet wurde, und es begrenzt sonst, wie viel das Verfahren später zählt (siehe
  [Glossar](../glossar/glossar.md), „Obergrenzen eines Verfahrens“). Die nativen Verfahren zählen
  deshalb ohne diese Obergrenze.
- Ein natives Verfahren steht nicht in `activeMethods`, der Liste der aktiven Verfahren eines
  Kontos. Es fehlt deshalb in der Verwaltung der Verfahren, in der Prüfung, ob sich jemand
  aussperrt, und beim Löschen des Kontos.
- Das Passwort lässt sich über Keycloak setzen, ohne Journey und ohne die Prüfungen auf
  Mindestniveau (Floor) und Frische des letzten Nachweises
  ([`MANAGE_AUTH_METHODS`](../journeys/manage-auth-methods.md)).
- Fehlversuche werden an zwei Stellen gezählt: in Keycloaks Schutz gegen das Durchprobieren von
  Passwörtern (Brute-Force-Schutz) und in der Kontosperre des Orchestrators.

## 2) Grundidee

**Jedes Verfahren ist ein Tool.** Auch für Passkey und OTP gibt es ein Modul mit einem Tool zum
Einrichten (Enroll) und einem Tool zum Anmelden (Auth), dazu einen Eintrag im Katalog der Tools und
eine Journey. Das Modul speichert kein Geheimnis, sondern nur einen Verweis auf das Credential in
Keycloak (`EnrollmentRef`). Ein Credential ist hier das gespeicherte Anmeldemerkmal, etwa der
registrierte Passkey. Genauso verweist das KOBIL-Modul heute auf das Gerät, das bei KOBIL
registriert ist.

**Keycloak ist ein Fremdsystem.** Keycloak speichert den Faktor, prüft ihn und meldet das Ergebnis.
Was daraus für das Konto folgt, entscheidet der Orchestrator.

**Im Web überlässt die Anzeige Keycloak die Arbeit.** Der `WebToolRenderer` eines solchen Tools
zeichnet kein eigenes Formular. Er ruft Keycloaks nativen Authenticator auf, fängt dessen Ergebnis
ab und meldet es dem Tool.

**Die App bietet diese Tools nicht an**, oder nur die, die ohne Keycloak auskommen. Festlegen, in
welchem Kanal ein Tool verfügbar ist, kann der Orchestrator schon heute
(`ToolAvailabilityService`).

## 3) Wo das technisch ansetzen würde

**Im Orchestrator**

- Je Verfahren gibt es ein Modul nach dem Muster von KOBIL, mit einer eigenen Tabelle für den
  Verweis auf das Credential.
- Der Controller des Tools nimmt die Meldung über den Abschluss nur von Keycloak an
  (`@BindingKey(keycloakOnly = true)`, wie heute `MgmtPasswordController`). Er liefert ein
  gewöhnliches `ToolOutcome`, also das Ergebnis, das jedes Tool liefert.
- Entfernt jemand das Verfahren, löscht das Modul das Credential in Keycloak
  (`EnrollmentCleanup`, `KeycloakAdminClient`). Bleibt dabei ein Credential versehentlich übrig,
  ist das unkritisch: Die Journey bietet ein Verfahren, das nicht mehr aktiv ist, nie an.

**In der Keycloak-Erweiterung**

- Der `WebToolRenderer` holt den nativen Authenticator über dessen Factory. Er ruft ihn mit einem
  umhüllten Kontext auf, also mit einer Hülle (Wrapper) um den normalen Kontext. Ohne Hülle meldet
  der native Authenticator einen Erfolg direkt an Keycloaks Ablauf. Die Hülle fängt Erfolg und
  Misserfolg ab und meldet beides zuerst dem Orchestrator.
- Beim Einrichten gilt dasselbe für die native Required Action. Eine Required Action ist ein
  Schritt, den Keycloak nach der Anmeldung verlangt. Sie wird aus
  `OrchestratorManageMethodsRequiredAction` aufgerufen.
- `OrchestratorNextDispatch` bleibt, wie es ist. Für diese Klasse ist ein solches Tool ein Tool wie
  jedes andere.

**Was entfiele**

- `NativeAuthenticatorRegistry` und die nativen Faktoren ohne Obergrenze.
- `OrchestratorUpdateAuthenticator`.
- Der zustandslose Weg für das Passwort (`OrchestratorStorageProvider.isValid`/`updateCredential`,
  `MgmtPasswordController`). Das Passwort bleibt ein Tool, dessen Geheimnis im Orchestrator liegt.
  Im Web kann sein Renderer Keycloaks Passwortseite als Vorlage nutzen.

## 4) Je Verfahren

Die Tabelle zeigt für jedes Verfahren, wo das Geheimnis liegt und wie es im Web und in der App
angeboten würde.

| Verfahren | Geheimnis | Web | App |
|---|---|---|---|
| Passwort | Orchestrator | Tool, Anzeige wie Keycloaks Passwortseite | Tool wie heute |
| OTP | Keycloak | Tool, überlässt die Anzeige Keycloaks OTP-Schritt | nicht angeboten |
| Passkey | Keycloak | Tool, überlässt die Anzeige Keycloaks WebAuthn-Schritt | nicht angeboten |

- **Soll OTP in beiden Kanälen laufen**, bleibt es ein eigenes Tool, dessen Geheimnis im
  Orchestrator liegt ([Beispiel neues Verfahren](../15-beispiel-neues-verfahren-backend.md)). Die
  Delegation an Keycloak lohnt sich nur, wenn das Web allein genügt.
- **Ein Passkey ohne Benutzernamen** braucht ein Tool, das das Konto aus dem Passkey ermittelt
  (`auth-passkey-lookup`).

## 5) Was sich an bestehendem Verhalten ändern würde

- Die Entscheidung aus ADR-8 kehrt sich für die nativen Verfahren um. Welches Verfahren an der
  Reihe ist, entscheidet dann die Journey. Bisher entscheidet das Keycloaks konfigurierter Ablauf
  mit Conditional-LoA, also mit Bedingungen auf das verlangte Sicherheitsniveau. Es bräuchte eine
  neue ADR, die ADR-8 in diesem Punkt ablöst.
- Für alle Verfahren gelten dieselben Regeln:
  - die Obergrenze `enrolledUnderAcr`,
  - das Mindestniveau (Floor),
  - die Frische des letzten Nachweises,
  - die Kontosperre,
  - die Liste `activeMethods`,
  - die Prüfung gegen Aussperren,
  - das Löschen mit dem Konto.
- Fehlversuche zählt nur noch der Orchestrator. Keycloaks Brute-Force-Schutz sieht die delegierten
  Schritte weiterhin, entscheidet aber nicht mehr über die Sperre.

## 6) Offene Fragen und Risiken

- **Lässt sich ein nativer Authenticator sauber umhüllen?** Auf dieser Annahme beruht die ganze
  Idee. Die Hülle nutzt die offizielle Schnittstelle für Authenticators (Authenticator-SPI). Sie
  ist aber vom inneren Verhalten der nativen Klassen abhängig, etwa von Auth-Notes und von den
  Action-URLs der laufenden Execution. Das muss bei jedem Keycloak-Upgrade erneut geprüft werden.
- **Zum Einrichten braucht es einen Keycloak-Nutzer.** Ein Passkey lässt sich erst registrieren,
  wenn das Konto existiert. Mitten in einer Registrierung, bei der es noch kein Konto gibt, geht das
  also nicht.
- **Wo liegt das Credential?** Keycloak liest die Konten über die Nutzer-Federation und übernimmt
  sie nicht in die eigene Datenbank ([ADR-38](../adr/ADR-038-keycloak-liest-konten.md)). Zu klären
  ist, ob Keycloak Credentials für solche Nutzer dauerhaft und zuverlässig unter derselben
  Nutzer-Id speichert.
- **Löschen an zwei Stellen.** Scheitert das Löschen in Keycloak, bleibt dort ein Credential ohne
  zugehöriges Verfahren übrig. Es braucht einen Abgleich oder die bewusste Entscheidung, dass das
  hinnehmbar ist.
- **Wiederherstellen des Kanals.** `OrchestratorResumeAuthenticator` und `restoreData` übertragen
  heute auch native Nachweise. Ohne native Schritte wird das einfacher. Wie viel sich dadurch
  ändert, ist noch zu prüfen. (Erledigt: [ADR-58](../adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md),
  [ADR-59](../adr/ADR-059-nachweise-je-keycloak-sitzung-im-orchestrator.md).)

## 7) Nächste Schritte

Zuerst kommt ein Spike mit OTP als kleinstem Fall, erst danach der Passkey. Der Spike soll zeigen:

- Ein Tool `auth-otp` funktioniert, dessen Renderer Keycloaks OTP-Schritt mit umhülltem Kontext
  aufruft.
- Erfolg, Fehlversuch und Abbruch erreichen den Orchestrator, bevor Keycloaks Ablauf weitergeht.
- Ein Neuladen der Seite und der Zurück-Knopf des Browsers brechen den Schritt nicht ab.

Funktioniert die Hülle, folgt der Rest dem Muster von KOBIL. Funktioniert sie nicht, bleibt es bei
ADR-8. Die nativen Verfahren melden dem Orchestrator dann nur, dass sie eingerichtet oder entfernt
wurden.

## 8) Stand des Spikes (2026-10-09)

**Was gebaut ist.** Der Code liegt nur auf dem Branch `spike/native-otp-wrapper`, nicht auf
`main` (ADR-58).

- Das Modul `auth_otp` (`src/main/kotlin/com/example/identity/tools/auth_otp/`) hat die Tools
  `enroll-otp` und `auth-otp` mit der Methode `otp`. Es speichert nur die Id des Credentials in
  Keycloak (`auth_otp.enrollment`). Beide Tools nehmen ein Ergebnis nur von Keycloak an
  (`KeycloakToolCalls.requireKeycloak`). Ein Erfolg bei `auth-otp` zählt nur für genau das
  Credential, das `enroll-otp` eingerichtet hat.
- In der Erweiterung umhüllen `InterceptingFlowContext` und `InterceptingRequiredActionContext`
  Keycloaks Kontexte
  (`keycloak-extension/src/main/java/com/example/identity/kcext/webtool/nativestep/`). Sie sind von
  Hand ausgeschrieben, kein dynamischer Proxy. Fügt Keycloak der Schnittstelle eine Methode hinzu,
  schlägt deshalb das Bauen fehl.
- `OtpAuthRendererFactory` ruft `OTPFormAuthenticator` auf, `OtpEnrollRendererFactory` die
  Required Action `UpdateTotp`. `OrchestratorAuthenticator` und
  `OrchestratorManageMethodsRequiredAction` fragen solche Renderer nach der Seite. Das abgeschickte
  Formular geben sie zuerst an den nativen Schritt. An den Orchestrator geht nur das Ergebnis,
  der Code selbst verlässt Keycloak nicht.
- Im App-Kanal sind beide Tools ausgeschaltet.

**Was aus dem Quelltext von Keycloak 26.6.4 und den Tests belegt ist.**

- **Die Hülle trägt.** `OTPFormAuthenticator` liest aus dem Kontext nur Nutzer, Realm, Sitzung,
  Formular, Ereignis und Anfrage. Es endet mit `success`, `failureChallenge`, `challenge` oder
  `forceChallenge`. Das Formular baut es über `context.form()` mit der Execution aus
  `context.getExecution()`. So schickt die Seite an die laufende Execution des Orchestrators
  zurück. `UpdateTotp` braucht ebenso nur `form()`, `getUser()`, `getRealm()` und die Anfrage.
- **Keycloaks Brute-Force-Schutz zählt nicht mehr mit.** Den Fehlversuch bucht Keycloak erst, wenn
  `failureChallenge` den Ablauf erreicht. Die Hülle fängt den Aufruf vorher ab, deshalb zählt nur
  der Orchestrator ([ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).
  `InterceptingFlowContextTest` hält das fest.
- **Wo das Credential liegt.** `OrchestratorStorageProvider` beansprucht nur `password`. Ein
  OTP-Credential speichert Keycloak deshalb in seinem Speicher für föderierte Nutzer, unter
  derselben Nutzer-Id.
- **`UpdateTotp` legt das Credential an, bevor es den Erfolg meldet.** Lehnt der Orchestrator die
  Meldung ab, bliebe es also übrig. Die Erweiterung löscht es in diesem Fall wieder
  (`NativeRequiredActionStep.rejected`). Die Id des neuen Credentials erfährt sie aus dem
  Vergleich der OTP-Credentials vor und nach dem Schritt.

**Was die Tests als Grenze zeigen.**

- **Die Registrierung bietet `enroll-otp` an.** `RegisterStrategyTest` und
  `RegisterEnrollFirstStrategyTest` zeigen es. In der Registrierung gibt es aber noch keinen
  Keycloak-Nutzer, und dort läuft auch keine Required Action. Die Erweiterung zeigt an dieser
  Stelle nur eine Fehlerseite. Es fehlt eine Deklaration am Tool, etwa „erst einrichtbar, wenn das
  Konto existiert“, die die Strategien beachten.
- **Jeder native Schritt läuft nur in seinem eigenen Keycloak-Kontext.** Ein Authenticator läuft
  nur im Anmeldeablauf, eine Required Action nur in der Verwaltung der Verfahren. Fordert die
  Verwaltung einen frischen Nachweis an und bietet dafür `auth-otp` an, zeigt die Erweiterung eine
  Fehlerseite (`WebFormRenderer.toolForm`).

**Was der Live-Lauf zeigt** (Compose-Stack mit Keycloak 26.6.4, Chrome, 2026-10-09).

Was funktioniert:

- **Einrichten.** Unter „Verfahren verwalten“ zeigt `enroll-otp` Keycloaks eigene Seite mit dem
  QR-Code. Das Formular schickt an die Verwaltung des Orchestrators zurück. Bei einem falschen
  ersten Code zeigt Keycloak dieselbe Seite mit seiner Fehlermeldung, der Orchestrator hört davon
  nichts. Ein richtiger Code führt zu „Anmeldeverfahren hinzugefügt“, und das Verfahren steht in
  der Liste.
- **Anmelden.** Nach dem Passwort bietet der Step-up auf `loa2` neben SMS auch `auth-otp` an.
  Keycloaks OTP-Seite läuft in der Execution des Orchestrators. Ein falscher Code kommt mit dem
  Text des Orchestrators zurück („Der Code ist ungültig. …“), er ist also dort als Fehlversuch
  angekommen. Ein richtiger Code schließt die Anmeldung ab, und Keycloak leitet mit
  Autorisierungscode zur Website zurück.
- **Neuladen und Zurück-Knopf** brechen den Schritt nicht ab. In der Anmeldung zeigt das Neuladen
  wieder die OTP-Seite. Schickt man eine Seite ab, die der Zurück-Knopf aus dem Cache holt,
  erkennt Keycloak die veraltete Aktion und zeigt den Schritt frisch. In der Verwaltung zeigt ein
  GET den laufenden nativen Schritt erneut, nicht die Liste.
- **Wo das Credential liegt.** Die Admin-API nennt beide OTP-Credentials unter der föderierten
  Nutzer-Id `f:<provider>:<accountId>`, der Nutzer ist nicht importiert (ADR-38). Nach einem
  Neustart von Keycloak liegen sie unverändert dort, mit denselben Ids.

Was nicht funktioniert:

- **Die Registrierung endet in einer Sackgasse.** Wählt man dort „Authenticator-App (Keycloak)“,
  erscheint „Dieses Anmeldeverfahren ist an dieser Stelle nicht möglich.“ ohne Weg zurück, auch
  nach dem Neuladen. Nur ein neuer Anmeldevorgang hilft.
- **Der Link „mode=manual“ zum Abtippen des Geheimnisses** zeigt auf Keycloaks eigene Required
  Action. `TotpBean.getManualUrl()` setzt `execution=CONFIGURE_TOTP` fest im Code. Keycloak
  antwortet mit „Diese Seite ist nicht mehr gültig“. Über „fortsetzen“ kommt man zum Schritt
  zurück. Keine Hülle kann das abfangen. Es ginge nur im Theme, das den Link umschreibt.
- **„Abbrechen“ beim Einrichten beendet die ganze Verwaltung.** Der Knopf `cancel-aia` wirkt auf die
  vom Client angestoßene Aktion insgesamt. Keycloak leitet mit `kc_action_status=cancelled` zur
  Website zurück, die Required Action sieht den Abbruch gar nicht.
- **Die native OTP-Seite hat weder „Zurück“ noch „Abbrechen“.** Wer `auth-otp` gewählt hat, kommt
  nicht mehr zu SMS, nur über einen neuen Anmeldevorgang.
- **Ein entferntes Verfahren bleibt in Keycloak wählbar.** Nach dem Entfernen bleibt das Credential
  in Keycloak liegen. Die OTP-Seite bietet es weiter an und wählt es sogar vor, weil es das ältere
  ist. Ein Code dafür zählt beim Orchestrator nicht, weil er nur das eingerichtete Credential
  annimmt. Für den Nutzer ist die Auswahl aber verwirrend. `UpdateTotp` verlangt außerdem beim
  erneuten Einrichten einen Gerätenamen, weil es das alte Credential noch sieht.
- Die Texte der nativen Seiten passen nicht immer, etwa „Sie müssen eine
  Mehrfachauthentifizierung einrichten, um das Benutzerkonto zu aktivieren“ beim freiwilligen
  Hinzufügen.

Nicht geprüft ist, ob Keycloaks Brute-Force-Schutz im Live-Lauf wirklich nichts bucht. Belegt ist
das nur durch den Quelltext und `InterceptingFlowContextTest`.

**Empfehlung.** Die Hülle trägt. Sie nutzt nur die Authenticator-SPI, Einrichten und Anmelden
laufen über Keycloaks eigene Schritte, und jedes Ergebnis erreicht zuerst den Orchestrator. Die
Schwierigkeiten liegen in den nativen Seiten selbst: Sie bringen eigene Links und Knöpfe mit, die
an Keycloaks Ablauf hängen (`mode=manual`, `cancel-aia`), und ihnen fehlen die Wege der
Tool-Seiten (Zurück, Abbrechen). Dazu kommt die Grenze aus den Tests: Ein natives Verfahren lässt
sich erst einrichten, wenn das Konto existiert. Vor dem Passkey braucht es deshalb drei Dinge:

- die Deklaration „erst nach dem Konto“,
- das Löschen des Credentials in Keycloak beim Entfernen,
- eine Entscheidung, ob das Theme die nativen Seiten um Zurück und Abbrechen ergänzt und den Link
  `mode=manual` umschreibt.

Gelingt das, lohnt eine neue ADR, die ADR-8 für die nativen Verfahren ablöst. Bleiben die nativen
Seiten so, wie Keycloak sie liefert, ist der Gewinn gegenüber ADR-8 kleiner als gedacht.
