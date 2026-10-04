# Abgleich mit dem externen Glossar

Dieses Dokument legt die Begriffe aus dem [externen Glossar](externes-glossar.md) neben dieses Projekt. Zu jedem
Begriff steht, was ihm im Projekt entspricht, und wo das Projekt bewusst anders vorgeht, warum.

Es richtet sich zuerst an den Autor des Glossars. Er muss den Code dafür nicht kennen: Abschnitt 1
erklärt die Wörter des Projekts, die danach vorkommen. Die Zeilen „Im Code“ sind für alle, die eine
Aussage nachprüfen wollen. Pfade im Code beziehen sich auf `src/main/kotlin/com/example/identity/`, sofern
nicht anders angegeben. Darunter liegen `contract/` (die Schnittstelle zu den Tools), `core/`
(Orchestrator und Konto), `tools/` (die einzelnen Verfahren) und `simulation/` (die simulierten
Fremdsysteme).

---

## 1) Das Projekt in Kürze

Das Projekt ist eine Demo für die Anmeldung und Identifizierung bei einer Krankenversicherung, über
eine App und über eine Webseite. Ein eigener Server, der **Orchestrator**, entscheidet, welche
Schritte ein Nutzer durchläuft. Keycloak stellt die Token für die Webseite aus und fragt dafür den
Orchestrator. Jedes Gerät spricht mit einem eigenen, nicht exportierbaren Schlüssel (DPoP), sodass
der Orchestrator es bei jeder Anfrage wiedererkennt.

Diese Wörter des Projekts kommen im Folgenden vor:

- **Anmeldeverfahren (im Code „Methode“):** was im Konto eingerichtet ist und eine Anmeldung
  ermöglicht, etwa Passwort, SMS, ein Geräteschlüssel oder die KOBIL-App.
- **Tool:** ein einzelner Ablauf, den der Nutzer durchläuft, etwa „SMS einrichten“ oder „mit SMS
  anmelden“. Zu einem Anmeldeverfahren gehören meist zwei Tools.
- **Identifizierungsverfahren:** ein Tool, das bestätigt, wer jemand ist: der Online-Ausweis, der
  Dienstleister Nect (Ausweis, Reisepass, EUDI-Wallet) und ein Freischaltcode per Brief.
- **Niveau (`loa1`, `loa2`, `loa3`), ausführlich Sicherheitsniveau:** wie sehr einer Anmeldung
  vertraut wird, gestaffelt nach NIST SP 800-63B, nah an den eIDAS-Niveaus niedrig, substanziell und
  hoch, aber nicht gleich. `loa1` reicht ein einzelnes Verfahren, `loa2` braucht zwei Faktortypen
  oder eine Identifizierung, `loa3` eine starke Identifizierung: mit dem Online-Ausweis, über Nect
  auch mit Personalausweis oder EUDI-Wallet.
- **Personenverzeichnis:** die simulierte Stammdatenhaltung der Versicherung. Sie kennt Personen mit
  Partnernummer, Namen, Geburtsdatum, Anschrift und gegebenenfalls Mitgliedsnummer (auch
  Versicherungsnummer genannt) und Krankenversichertennummer (KVNR).
- **Angabe (Claim):** ein Eintrag im Konto, dass ein Attribut einen bestimmten Wert hat, mit der
  Quelle, die dafür einsteht, ihrer Stufe (*belegt*, *nachgewiesen*, *behauptet*) und dem Niveau,
  unter dem sie entstand.
- **Anker:** ein Attribut, über das ein Konto eindeutig wiedergefunden wird, etwa die Partnernummer,
  die Mitgliedsnummer, die Kennung eines Ausweises oder die bestätigte E-Mail-Adresse.
- **Bestätigen:** ein Tool prüft eine Angabe, etwa die E-Mail-Adresse per Code. Das ist weder eine
  Anmeldung noch eine Identifizierung und hebt das Niveau nicht.
- **Korrelation (Zuordnen):** ein Tool ordnet eine schon bescheinigte Identität einer Person im
  Personenverzeichnis zu, etwa über die KVNR. Es beweist selbst nichts.
- **Rolle:** ein Konto ohne zugeordnete Person ist ein **Interessent**, mit Person ein **Partner**,
  mit Mitgliedsnummer ein **Versicherter**.
- **Geräteverknüpfung:** dass der DPoP-Schlüssel eines Geräts einem Konto zugeordnet ist. Sie
  erkennt das Gerät wieder und zählt ausdrücklich nicht als Anmeldung.

## 2) Welche Wörter einander entsprechen

Die Doku benutzt die Wörter des Projekts. Das [Glossar des Projekts](glossar.md) und diese Seite
legen diese Entsprechungen fest:

- **Authentisierungsmittel** heißt im Projekt Anmeldeverfahren.
- **Identifizierungsmittel** heißt Identifizierungsverfahren.
- **Authentisierung und Authentifizierung** fasst das Projekt unter „Authentifizierung“ zusammen.
  In der Sache ist die Trennung da: Das Tool liefert den Nachweis für den Client, die Policy des
  Servers prüft ihn.
- **Bescheinigtes Attribut** ist eine Angabe der Stufe *belegt* oder *nachgewiesen*: Ein
  Identifizierungsverfahren oder das Personenverzeichnis steht für sie ein. Eine Angabe der Stufe
  *behauptet* ist unbescheinigt. Im Code heißt die Stufe `ClaimTrust`.
- **Faktortyp** heißt im Projekt ebenso, im Code `FactorType`. Biometrie heißt dort `INHERENCE`.
- **Gerätebindung** meint nur das Einrichten eines an das Gerät gebundenen Anmeldeverfahrens
  (Geräteschlüssel, KOBIL). Die Zuordnung des DPoP-Schlüssels zu einem Konto heißt
  Geräteverknüpfung.
- **Identifizierung** teilt das Projekt in Schritte auf: Ein Identifizierungsverfahren bescheinigt,
  wer jemand ist. Bestätigen prüft ein einzelnes Attribut wie die E-Mail-Adresse. Die Korrelation
  ordnet die bescheinigte Identität einer Person im Personenverzeichnis zu. ADR-17, ADR-18.
- **Mehrstufige Authentifizierung** heißt die Kombination zweier einzeln geprüfter Verfahren, etwa
  SMS und Passwort. „MFA“ steht nur für ein einzelnes Verfahren mit zwei verknüpften Faktoren.

Die Oberfläche hält dieselbe Unterscheidung: Ein Geräteschlüssel heißt dort „an das Gerät
gebunden“, die Geräteverknüpfung „Verknüpfung“. Die Vorgabe für die Übersetzung schreibt das fest.

Im Code: `.claude/skills/translate-texts/prompts/de.md` (Übersetzungsvorgabe).

## 3) Wo das Projekt dem Glossar folgt

- **Faktortypen Wissen, Besitz und Biometrie.** Jedes Anmeldeverfahren nennt seine Faktortypen
  selbst. Das Passwort ist das Verfahren mit nur einem Faktor aus dem Glossar, die SMS ebenso mit dem
  Faktor Besitz.
  Im Code: `FactorType` in `contract/tool_api/Tool.kt`;
  `tools/auth_password/PasswordToolModule.kt`, `tools/auth_sms/SmsToolModule.kt`.
- **Nachweis beim Authentisieren.** Jedes Tool liefert einen Nachweis, die Sitzung sammelt die
  Nachweise, und die Policy leitet daraus das Niveau ab.
  Im Code: `SessionEvidence` in `core/orchestrator/domain/policy/`.
- **2-Faktor-Authentisierungsmittel nach Beispiel 2 und Gerätebindung für MFA.** Der Geräteschlüssel
  ist ein eigenes, nicht exportierbares Schlüsselpaar, das der Nutzer auf dem Gerät per PIN oder
  Biometrie freigibt. Die Faktortypen werden bei jedem Nachweis aus der tatsächlichen Freigabe
  abgeleitet: PIN ergibt Besitz und Wissen, Biometrie ergibt Besitz und Biometrie. KOBIL macht es
  ebenso.
  Im Code: `frontend/src/deviceKey.ts`;
  `tools/auth_device/internal/authdevice/AuthDeviceToolHandler.kt`;
  `tools/auth_kobil/internal/KobilFactors.kt`. In der Doku: [Verfahren `device`](../verfahren/device.md)
  und [`kobil`](../verfahren/kobil.md).
- **Faktortyp Besitz: ein nicht kopierbarer Schlüssel erkennt das Gerät, solange er existiert.**
  Jedes Gerät spricht mit einem Schlüssel, der die Anwendung nicht verlassen kann. Das Konto merkt
  sich dessen Fingerabdruck. Wird ein Gerät einem anderen Konto zugeordnet, werden alle an diesen
  Schlüssel gebundenen Anmeldeverfahren widerrufen. Wie sicher der Schlüssel im Browser liegt, steht
  in Abschnitt 4.
  Im Code: `frontend/src/dpop.ts`. In der Doku: [DPoP-Bindung](../09-dpop.md), Abschnitte 1 bis 3.
- **Bei der Gerätebindung muss der Server dem Gerät vertrauen.** Geräteschlüssel und KOBIL melden
  Wissen oder Biometrie aus einer Freigabe auf dem Gerät, die der Server nicht sehen kann. Das
  Projekt schreibt dieses Vertrauen als benannte Ausnahme fest, statt es stillschweigend
  vorauszusetzen. Weil der Server die Freigabe nicht nachprüfen kann, gibt es den Geräteschlüssel
  nur im Demomodus. Dasselbe gilt für KOBIL, solange der Anbieter simuliert ist.
  Im Code: `contract/tool_api/device/DeviceProofs.kt`; `ToolModule.demoOnly`.
  In der Doku: [Orchestrierung](../04-orchestrierung.md), Abschnitt 4; ADR-21 und ADR-36 in den
  [Entscheidungen](../12-entscheidungen.md).
- **Bescheinigte und unbescheinigte Attribute bleiben unterscheidbar.** Jede Angabe speichert ihre
  Quelle, und daraus folgt ihre Stufe: *belegt* durch das Personenverzeichnis, *nachgewiesen*
  durch ein Identifizierungs- oder Bestätigungsverfahren, *behauptet*, wenn nur der Nutzer sie
  angibt.
  Voraussetzungen nennen die nötige Stufe: Ein Passwort lässt sich erst einrichten, wenn die
  E-Mail-Adresse bestätigt ist. Ein nur behaupteter Wert erfüllt keine Voraussetzung, die einen
  bestätigten verlangt.
  **Einschränkung:** Kein Tool meldet heute selbst einen nur behaupteten Wert. Die Stufe ist
  vorhanden und wird geprüft, aber noch nicht genutzt.
  Im Code: `ClaimSource` und `ClaimTrust` in `contract/tool_api/claims/Claims.kt`; `requires` in
  `tools/auth_password/PasswordToolModule.kt`.
- **Identifizierungsmittel mit einem Bezeichner, der für das Mittel selbst eindeutig ist.** Der
  Online-Ausweis liefert seine Kennung für diesen Diensteanbieter (`restrictedId`). Das Konto hält
  sie als eigenen Anker, und über ihn wird die Person wiedererkannt, nicht über Name und
  Geburtsdatum. Liest Nect die Karte, entsteht Nects eigenes Pseudonym als weiterer Anker. Weil das
  Pseudonym je Diensteanbieter verschieden ist (§ 18 PAuswG), erkennt jeder Weg die Karte nur für
  sich wieder. Eine neue Karte ersetzt den Wert.
  Im Code: Anker `EID_RESTRICTED_ID` und `NECT_RESTRICTED_ID` in
  `contract/tool_api/claims/AttributeType.kt`; `tools/ident_eid/EidToolModule.kt`;
  `tools/ident_nect/internal/IdentNectToolHandler.kt`. ADR-19.
- **E-Mail-Konto mit Einmalcode als Identifizierungsmittel.** Das Tool „E-Mail bestätigen“ prüft einen
  Code und schreibt die Adresse als bestätigten Anker (Stufe *nachgewiesen*). Wie im Glossar vertraut
  der Server dabei stillschweigend der Anmeldung beim E-Mail-Anbieter. Das Projekt nennt diesen
  Schritt Bestätigen, nicht Identifizierung: Er ordnet keine Person zu und hebt das Niveau nicht.
  Erst ein zweites Tool macht die bestätigte Adresse auf Wunsch zu einem Anmeldeverfahren.
  Im Code: `confirm-email` (Tool-Rolle `ATTESTATION`) und `enroll-email` in
  `tools/auth_email/EmailToolModule.kt`; `EMAIL_ANCHOR_ENROLLMENT` in
  `contract/tool_api/directory/AccountDirectory.kt`. ADR-17.
- **Identifizierung als Anreicherung eines schon wiedererkannten Clients, auch in einem späteren
  Schritt.** Online-Ausweis oder Nect bestätigen zuerst, wer jemand ist. Danach ordnet die Eingabe der
  KVNR diese Person im Personenverzeichnis zu. Ein Konto, das zuerst nur ein Anmeldeverfahren
  einrichtet und sich später ausweist, folgt demselben Muster. Das entspricht dem Beispiel mit
  Bahnticket und Personalausweis.
  In der Doku: [Verfahren `eid`](../verfahren/eid.md) und [`kvnr`](../verfahren/kvnr.md). ADR-18.
- **Biometrie am Beispiel des Ausweisfotos.** Nect mit Reisepass liest den Chip und vergleicht das
  Lichtbild mit dem Gesicht. Das ergibt Besitz und Biometrie. Weil das den Inhaber schwächer bindet
  als die PIN des Ausweises, reicht es nur für `loa2`. Der Dienstleister ist simuliert.
  Im Code: `tools/ident_nect/internal/IdentNectToolHandler.kt`.
- **ID-Server.** Orchestrator und Keycloak zusammen verwalten Konten und ihre Attribute und geben
  sie mit dem Stand der Authentifizierung im Token weiter: Niveau, benutzte Verfahren, Partnernummer
  und Mitgliedsnummer. Die Personen selbst verwaltet das Personenverzeichnis. Ändert es eine
  Person, ziehen die Konten per Ereignis nach. Keycloak hält keine eigene Kopie der Konten, sondern
  liest sie beim Orchestrator nach.
  Im Code: `core/orchestrator/session/TokenService.kt` (Tokens ohne Keycloak);
  `core/account/application/PersonChangeListener.kt`. ADR-34, ADR-38.
- **Mehrstufige Authentifizierung gegenüber 2FA.** SMS und Passwort sind zwei einzeln geprüfte
  Verfahren. Sie erreichen zusammen `loa2` und heißen in der Doku mehrstufige Authentifizierung.
  NIST SP 800-63B stellt sie für AAL2 der MFA gleich, daher dasselbe Niveau.
  In der Doku: [Orchestrierung](../04-orchestrierung.md), Abschnitt 4, „IAL und AAL“.

## 4) Wo das Projekt dem Glossar nur mit Einschränkung folgt

- **Challenge-Response.** Beim Geräteschlüssel stellt der Server keine eigene Challenge. Der
  signierte Geräte-Proof ist stattdessen an genau diese Anfrage gebunden (Methode, Adresse, Zeitpunkt,
  einmalige Kennung) und gegen Wiederholung geschützt. Bei KOBIL fragt der Server die Bestätigung
  selbst beim Anbieter ab. Beides erfüllt den Zweck, ist aber kein Challenge-Response im engeren
  Sinn.
  In der Doku: [Verfahren `device`](../verfahren/device.md); [DPoP-Bindung](../09-dpop.md),
  Abschnitt 2. ADR-21.
- **Sicherer Speicher.** Die Schlüssel liegen im Browser, über die Web Crypto API als nicht
  exportierbar erzeugt. Ihr Wert lässt sich über keine Programmierschnittstelle auslesen. Das kommt
  der Definition nahe, eine Garantie durch Hardware und eine Zertifizierung gibt es aber nicht. Das
  Entsperrgeheimnis von KOBIL liegt sogar nur im lokalen Speicher des Browsers. Das Projekt ist eine
  Demo und sagt das an dieser Stelle selbst.
  Im Code: `frontend/src/deviceKey.ts`, `frontend/src/dpop.ts`, `frontend/src/kobilUnlockSecret.ts`.
  In der Doku: [DPoP-Bindung](../09-dpop.md), „Wie sicher die Schlüssel im Browser sind“.
- **Server, Client und Person.** Das Glossar setzt Client und Person teilweise gleich. Das Projekt
  trennt drei Dinge: das Gerät (über seinen Schlüssel), das Konto und die Person im
  Personenverzeichnis. Ein wiedererkanntes Gerät sagt nur, welches Gerät spricht, nicht, wer davor
  sitzt. Das verfeinert das Glossar, ohne ihm zu widersprechen.
- **Versicherungs-Smartcard als Identifizierungsmittel.** Eine Gesundheitskarte gibt es im Projekt
  nicht. Die KVNR wird eingetippt, nicht bescheinigt. Die Eingabe darf deshalb erst laufen, wenn
  Name, Vorname und Geburtsdatum bereits bescheinigt sind. Zugeordnet wird nur, wenn die Person hinter
  der Nummer zu diesen Angaben passt. Für die Partnernummer gilt dasselbe.
  Im Code: `tools/ident_kvnr/KvnrToolModule.kt` (Tool-Rolle `CORRELATION`, `requires`);
  `core/account/application/IdentityMatchingService.kt`.
- **Unbescheinigte Attribute nie für die Zuordnung zu einem Stammdatensatz.** Beim Freischaltcode
  tippt die Person Nummer, Namen und Geburtsdatum ein, also unbescheinigte Angaben. Sie finden aber
  nur den Datensatz. Wofür das Personenverzeichnis einsteht, ist der Code aus dem Brief, den es selbst
  an diese Person geschickt hat. Das ist der Einmalcode aus dem Glossar, ein schwächeres,
  nicht kryptographisches Verfahren. Deshalb erreicht der Freischaltcode nur `loa2`, der
  Online-Ausweis `loa3`.
  Im Code: `tools/ident_fsc/internal/IdentFscToolHandler.kt`;
  `simulation/personenverzeichnis/Freischaltcodes.kt`.
  In der Doku: [Verfahren `fsc`](../verfahren/fsc.md).
- **SIM-Karte mit Telefonnummer als Identifizierungsmittel.** Die SMS dient nur als Anmeldeverfahren
  mit dem Faktor Besitz. Die Telefonnummer wird festgehalten, dient aber nie dazu, eine Person zu
  erkennen: Sie ist kein Anker, sondern gehört dem Modul des Verfahrens. Das Projekt nutzt die
  SIM-Karte bewusst nicht als Identifizierungsmittel.
  Im Code: `tools/auth_sms/SmsToolModule.kt`; `PHONE_NUMBER` in
  `tools/auth_sms/SmsToolModule.kt`.

## 5) Was auf einer anderen Ebene liegt

- **Kommunikationspartner, sichere Kommunikation, sicherer Kommunikationskanal.** Das sind Begriffe
  der Transportschicht. Das Projekt setzt HTTPS voraus und beschreibt es nicht als eigenen
  fachlichen Begriff.
- **Nachricht.** Am nächsten kommt der DPoP-Proof: Jede Anfrage trägt einen eigenen signierten
  Proof, der nur für sie gilt und sich nicht wiederholen lässt. Einen eigenen Begriff „Nachricht“
  gibt es nicht.
  In der Doku: [DPoP-Bindung](../09-dpop.md), Abschnitt 1.
- **Identität als eine Sammlung von Attributen.** Dazu der nächste Abschnitt.

## 6) Identität: eine Sammlung oder mehrere Bausteine

Das Glossar beschreibt Identität als **eine** Sache: eine Sammlung von Attributen, die einem Client
oder einer Person zugeordnet sind. Das Projekt verteilt dasselbe auf Bausteine, die unterschiedlich
lange leben. Keiner davon ist allein die Identität des Glossars.

1. **Das Konto trägt keine Attribute.** Es hat nur eine Nummer, einen Zeitpunkt der Anlage und eine
   Versionsnummer. Es ist die Stelle, an der sich alles andere festmacht.
2. **Claims sind die Geschichte.** Jede Änderung einer Angabe wird mit Quelle und Niveau angehängt
   und nie überschrieben; die Stufe folgt aus der Quelle. Widerrufe nehmen Werte zurück, etwa wenn das Personenverzeichnis eine
   Mitgliedsnummer ändert.
3. **Die Anker sind der aktuelle Stand.** Partnernummer, Mitgliedsnummer, die Kennungen des
   Ausweises und die E-Mail-Adresse stehen in eigenen Zeilen je Konto, höchstens ein Wert je Art.
   Dort ist ihr einziger Speicherort; die Claims halten nur fest, wie sie entstanden sind.
4. **Stammdaten bleiben im Personenverzeichnis.** Name, Geburtsdatum, Anschrift und KVNR einer
   zugeordneten Person liest das Projekt immer frisch von dort. Im Konto stehen sie nur als
   Geschichte. Die Telefonnummer der SMS hält das Modul dieses Verfahrens.
5. **Die Rolle wird abgeleitet, nicht gespeichert.** Interessent, Partner oder Versicherter folgt aus
   Partnernummer und Mitgliedsnummer. Ein Konto kann schon einen bestätigten Namen haben und
   trotzdem Interessent sein, solange ihm keine Person zugeordnet ist.
6. **Das Änderungsprotokoll ist Beleg, keine Identität.** Es hält unter anderem fest, dass und wie
   identifiziert wurde, wird für keine Entscheidung gelesen und überlebt das Konto.

**Einordnung:** Der aktuelle Stand der Anker, ergänzt um die Stammdaten aus dem Personenverzeichnis,
entspricht zu jedem Zeitpunkt der Identität des Glossars. Die übrigen Bausteine beantworten Fragen,
die das Glossar voraussetzt, aber nicht stellt: Woher kommt ein Wert? Seit wann gilt er als
bescheinigt? Was geschieht, wenn zwei Bestätigungen einander widersprechen? Das ist eine
Verfeinerung, kein Widerspruch.

Im Code: `core/account/infrastructure/Account.kt`; `core/account/application/ClaimLedger.kt`;
`core/account/AccountService.kt`; `contract/tool_api/claims/AttributeType.kt`. In der Doku:
[Domänenmodell](../02-domaenenmodell.md), Abschnitt 6 „Konto-Identität: Claims, Anker,
Konsolidierung“; das Änderungsprotokoll in ADR-39.

## 7) Bewusste Abweichungen und ihre Begründung

Diese Punkte weichen vom Glossar ab. Das Projekt hält sie für richtig und möchte sie mit dem Autor
diskutieren.

1. **Ein Konto ohne zugeordnete Person.** Das Glossar sagt nicht, ob die Zuordnung zu einer Person
   schon besteht. Das Projekt trennt „kann sich dieses Gerät wieder anmelden“ von „wissen wir, wer
   das ist“, und danach, ob die Person bei der Versicherung versichert ist. Sobald eine Person
   zugeordnet ist, verhält sich das Konto so, wie das Glossar es beschreibt. ADR-10, ADR-34.
2. **Claims mit Geschichte statt einer Sammlung von Attributen.** Der aktuelle Stand entspricht der
   Identität des Glossars. Die Geschichte braucht es, um später zu beantworten, warum zu einem
   Zeitpunkt ein Wert als bescheinigt galt. Im Gesundheitswesen ist das ein Gewinn.
3. **Drei Stufen statt bescheinigt oder unbescheinigt.** Das Glossar deutet selbst Wege
   unterschiedlicher Stärke an: Zertifizierung gegenüber weniger sicheren, nicht kryptographischen
   Verfahren. Das Projekt macht diese Abstufung ausdrücklich und prüft sie maschinell.
4. **Konten werden über Anker gefunden, nie über Namen, und nie selbsttätig zusammengelegt.** Nur ein
   verwerfbares Konto, ohne Person und ohne je eingerichtetes Verfahren, geht im gefundenen auf. Das
   Glossar beschreibt die Identifizierung als Ergebnis. Das Projekt beschreibt auch den Vorgang: was
   geschieht, wenn eine neue Bestätigung auf ein bestehendes Konto trifft. ADR-11, ADR-19, ADR-20.
5. **Faktortypen je Nachweis statt fest je Verfahren.** Das entspricht dem Glossar (Abschnitt 3) und
   geht nur in der Sorgfalt darüber hinaus.
6. **Vertrauen in die Freigabe auf dem Gerät.** Dass Geräteschlüssel und KOBIL Wissen oder Biometrie
   melden, die der Server nicht sieht, ist genau das Vertrauen in das Gerät, das das Glossar bei der
   Gerätebindung selbst verlangt. Solange der Server die Freigabe nicht prüfen kann, läuft das nur im
   Demomodus. ADR-21, ADR-36.
7. **Freischaltcode trotz eingetippter Angaben.** Die Angaben finden nur den Datensatz, bescheinigt
   wird durch den Code aus dem Brief. Dass das schwächer ist als der Online-Ausweis, zeigt das
   niedrigere Niveau.

Die Entscheidungen hinter den genannten ADR-Nummern stehen in den
[Entscheidungen](../12-entscheidungen.md).
