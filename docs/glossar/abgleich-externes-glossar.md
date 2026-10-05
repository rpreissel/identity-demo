# Abgleich mit dem externen Glossar

Dieses Dokument stellt die Begriffe aus dem [externen Glossar](externes-glossar.md) neben die
Begriffe dieses Projekts. Zu jedem Begriff steht, was ihm im Projekt entspricht. Wo das Projekt
bewusst anders vorgeht, steht auch, warum.

Das Dokument richtet sich zuerst an den Autor des externen Glossars. Er muss dafür den Code nicht
kennen: Abschnitt 1 erklärt die Wörter des Projekts, die danach vorkommen. Die Zeilen „Im Code“ sind
für alle gedacht, die eine Aussage im Code nachprüfen wollen. Pfade im Code beziehen sich auf
`src/main/kotlin/com/example/identity/`, wenn nichts anderes angegeben ist. Darunter liegen vier
Bereiche:

- `contract/`: die Schnittstelle zu den Tools,
- `core/`: der Orchestrator und das Konto,
- `tools/`: die einzelnen Verfahren,
- `simulation/`: die simulierten Fremdsysteme.

---

## 1) Das Projekt in Kürze

Das Projekt ist eine Demo für die Anmeldung und Identifizierung bei einer Krankenversicherung. Nutzer
melden sich über eine App oder über eine Webseite an. Ein eigener Server, der **Orchestrator**,
entscheidet, welche Schritte ein Nutzer dabei durchläuft. Für die Webseite stellt Keycloak die
Tokens aus, also die signierten Belege über die Anmeldung. Welche Schritte nötig sind, fragt Keycloak
dafür beim Orchestrator. Die App weist sich bei jeder Anfrage mit einem eigenen Schlüssel aus, der
sich nicht exportieren lässt (DPoP). So erkennt der Orchestrator das Gerät bei jeder Anfrage wieder.
Die Webseite spricht nie direkt mit dem Orchestrator, sondern immer über Keycloak.

Diese Wörter des Projekts kommen im Folgenden vor:

- **Anmeldeverfahren (im Code „Methode“):** etwas, das im Konto eingerichtet ist und mit dem sich
  der Inhaber anmelden kann, etwa ein Passwort, SMS, ein Geräteschlüssel oder die KOBIL-App.
- **Tool:** ein einzelner Ablauf, den der Nutzer durchläuft, etwa „SMS einrichten“ oder „mit SMS
  anmelden“. Zu einem Anmeldeverfahren gehören meist zwei Tools.
- **Identifizierungsverfahren:** ein Tool, das feststellt, wer jemand wirklich ist. Es gibt drei
  davon: den Online-Ausweis, den Dienstleister Nect (mit Ausweis, Reisepass oder EUDI-Wallet) und
  einen Freischaltcode, den die Versicherung per Brief schickt.
- **Niveau (`loa1`, `loa2`, `loa3`), ausführlich Sicherheitsniveau:** wie sehr einer Anmeldung
  vertraut wird. Die Stufen folgen NIST SP 800-63B. Sie liegen nah an den eIDAS-Niveaus niedrig,
  substanziell und hoch, sind ihnen aber nicht gleich. Für `loa1` reicht ein einzelnes Verfahren.
  `loa2` braucht zwei Faktortypen oder eine Identifizierung. `loa3` braucht eine starke
  Identifizierung: mit dem Online-Ausweis oder über Nect auch mit Personalausweis oder EUDI-Wallet.
- **Personenverzeichnis:** die simulierte Stammdatenhaltung der Versicherung. Sie kennt Personen mit
  Partnernummer, Namen, Geburtsdatum und Anschrift. Bei Versicherten kommen die Mitgliedsnummer (auch
  Versicherungsnummer genannt) und die Krankenversichertennummer (KVNR) dazu.
- **Angabe (Claim):** ein Eintrag im Konto, der festhält, dass ein Attribut einen bestimmten Wert
  hat. Zu jeder Angabe gehören die Quelle, die sie geliefert hat und für ihre Richtigkeit
  verantwortlich ist, ihre Stufe (*belegt*, *nachgewiesen*, *behauptet*) und das Niveau, unter dem
  sie entstand.
- **Anker:** ein Attribut, über das sich ein Konto eindeutig wiederfinden lässt, etwa die
  Partnernummer, die Mitgliedsnummer, die Kennung eines Ausweises oder die bestätigte
  E-Mail-Adresse.
- **Bestätigen:** Ein Tool prüft eine Angabe, etwa die E-Mail-Adresse mit einem Code. Das ist weder
  eine Anmeldung noch eine Identifizierung und erhöht das Niveau nicht.
- **Korrelation (Zuordnen):** Ein Tool ordnet eine schon bescheinigte Identität einer Person im
  Personenverzeichnis zu, etwa über die KVNR. Es beweist selbst nichts.
- **Rolle:** Ein Konto ohne zugeordnete Person gehört einem **Interessenten**. Ist ihm eine Person
  zugeordnet, gehört es einem **Partner**. Hat diese Person auch eine Mitgliedsnummer, gehört es
  einem **Versicherten**.
- **Geräteverknüpfung:** Der DPoP-Schlüssel eines Geräts ist einem Konto zugeordnet. Damit wird das
  Gerät wiedererkannt. Das zählt ausdrücklich nicht als Anmeldung.
- **Vorgangszugang:** Das Personenverzeichnis lädt eine Person per Brief zu einem bestimmten
  Vorgang ein. Im Brief steht ein **Einmalkennwort**. Damit meldet sich die Person auf der Webseite
  an, auch wenn sie kein Konto hat. Die Anmeldung gilt nur für diesen einen Vorgang.
- **Anmeldung mit der App bestätigen (QR-Login):** Der Nutzer meldet sich auf der Webseite an, indem
  er die Anmeldung in der App bestätigt, in der er schon angemeldet ist. Der Browser zeigt dazu einen
  QR-Code, die App danach einen Bestätigungscode, den der Nutzer im Browser eintippt.

## 2) Welche Wörter einander entsprechen

Die Doku benutzt die Wörter des Projekts. Das [Glossar des Projekts](glossar.md) und diese Seite
legen fest, welches Wort des externen Glossars welchem Wort des Projekts entspricht:

- **Authentisierungsmittel** heißt im Projekt Anmeldeverfahren.
- **Identifizierungsmittel** heißt Identifizierungsverfahren.
- **Authentisierung und Authentifizierung** fasst das Projekt unter „Authentifizierung“ zusammen.
  In der Sache gibt es die Trennung aber: Das Tool liefert den Nachweis für den Client, und die
  Regeln des Servers (die Policy) prüfen ihn.
- **Bescheinigtes Attribut** entspricht einer Angabe der Stufe *belegt* oder *nachgewiesen*. Für
  eine solche Angabe ist ein Identifizierungsverfahren oder das Personenverzeichnis verantwortlich.
  Eine Angabe der Stufe *behauptet* ist unbescheinigt. Im Code heißt die Stufe `ClaimTrust`.
- **Faktortyp** heißt im Projekt ebenso, im Code `FactorType`. Biometrie heißt dort `INHERENCE`.
- **Gerätebindung** meint nur das Einrichten eines Anmeldeverfahrens, das an das Gerät gebunden ist
  (Geräteschlüssel, KOBIL). Dass der DPoP-Schlüssel eines Geräts einem Konto zugeordnet ist, heißt
  dagegen Geräteverknüpfung.
- **Identifizierung** teilt das Projekt in mehrere Schritte auf. Ein Identifizierungsverfahren
  bescheinigt, wer jemand ist. Bestätigen prüft ein einzelnes Attribut wie die E-Mail-Adresse. Die
  Korrelation ordnet die bescheinigte Identität einer Person im Personenverzeichnis zu. Mehr dazu in
  ADR-17 und ADR-18.
- **Mehrstufige Authentifizierung** heißt die Kombination zweier Verfahren, die einzeln geprüft
  werden, etwa SMS und Passwort. „MFA“ steht nur für ein einzelnes Verfahren mit zwei verknüpften
  Faktoren.

Die Oberfläche macht denselben Unterschied: Ein Geräteschlüssel heißt dort „an das Gerät gebunden“,
die Geräteverknüpfung heißt „Verknüpfung“. Die Vorgabe für die Übersetzung der Oberflächentexte
schreibt das fest.

Im Code: `.claude/skills/translate-texts/prompts/de.md` (Übersetzungsvorgabe).

## 3) Wo das Projekt dem Glossar folgt

- **Faktortypen Wissen, Besitz und Biometrie.** Jedes Anmeldeverfahren nennt seine Faktortypen
  selbst. Das Passwort entspricht dem Verfahren mit nur einem Faktor aus dem Glossar. Dasselbe gilt
  für die SMS, deren einziger Faktor Besitz ist.
  Im Code: `FactorType` in `contract/tool_api/Tool.kt`;
  `tools/auth_password/PasswordToolModule.kt`, `tools/auth_sms/SmsToolModule.kt`.
- **Nachweis beim Authentisieren.** Jedes Tool liefert einen Nachweis. Die Sitzung sammelt die
  Nachweise, und die Regeln des Servers (die Policy) berechnen daraus das Niveau.
  Im Code: `SessionEvidence` in `core/orchestrator/domain/policy/`.
- **2-Faktor-Authentisierungsmittel nach Beispiel 2 und Gerätebindung für MFA.** Der Geräteschlüssel
  ist ein eigenes Schlüsselpaar, das sich nicht exportieren lässt. Der Nutzer gibt ihn auf dem Gerät
  per PIN oder Biometrie frei. Die Faktortypen werden bei jedem Nachweis aus der tatsächlichen
  Freigabe abgeleitet: Eine PIN ergibt Besitz und Wissen, Biometrie ergibt Besitz und Biometrie.
  KOBIL macht es ebenso.
  Im Code: `frontend/src/deviceKey.ts`;
  `tools/auth_device/internal/authdevice/AuthDeviceToolHandler.kt`;
  `tools/auth_kobil/internal/KobilFactors.kt`. In der Doku: [Verfahren `device`](../verfahren/device.md)
  und [`kobil`](../verfahren/kobil.md).
- **Faktortyp Besitz: ein nicht kopierbarer Schlüssel erkennt das Gerät, solange er existiert.**
  Jedes Gerät weist sich mit einem Schlüssel aus, der die Anwendung nicht verlassen kann. Das Konto
  speichert dessen Fingerabdruck. Wird ein Gerät einem anderen Konto zugeordnet, werden alle
  Anmeldeverfahren widerrufen, die an diesen Schlüssel gebunden sind. Wie sicher der Schlüssel im
  Browser liegt, steht in Abschnitt 4.
  Im Code: `frontend/src/dpop.ts`. In der Doku: [DPoP-Bindung](../09-dpop.md), Abschnitte 1 bis 3.
- **Bei der Gerätebindung muss der Server dem Gerät vertrauen.** Geräteschlüssel und KOBIL melden
  Wissen oder Biometrie aus einer Freigabe auf dem Gerät. Diese Freigabe kann der Server nicht
  sehen. Das Projekt hält dieses Vertrauen ausdrücklich als benannte Ausnahme fest, statt es
  stillschweigend vorauszusetzen. Weil der Server die Freigabe nicht nachprüfen kann, gibt es den
  Geräteschlüssel nur im Demomodus. Dasselbe gilt für KOBIL, solange der Anbieter simuliert ist.
  Im Code: `contract/tool_api/device/DeviceProofs.kt`; `ToolModule.demoOnly`.
  In der Doku: [Orchestrierung](../04-orchestrierung.md), Abschnitt 4; ADR-21 und ADR-36 in den
  [Entscheidungen](../12-entscheidungen.md).
- **Bescheinigte und unbescheinigte Attribute bleiben unterscheidbar.** Jede Angabe speichert ihre
  Quelle, und aus der Quelle folgt ihre Stufe:
  - *belegt*, wenn sie vom Personenverzeichnis stammt,
  - *nachgewiesen*, wenn ein Identifizierungs- oder Bestätigungsverfahren sie geprüft hat,
  - *behauptet*, wenn nur der Nutzer sie angegeben hat.

  Die Voraussetzungen eines Tools nennen die nötige Stufe. Ein Passwort lässt sich zum Beispiel erst
  einrichten, wenn die E-Mail-Adresse bestätigt ist. Ein nur behaupteter Wert erfüllt keine
  Voraussetzung, die einen bestätigten Wert verlangt.
  **Einschränkung:** Heute meldet noch kein Tool selbst einen nur behaupteten Wert. Die Stufe gibt es
  und sie wird geprüft, sie wird aber noch nicht genutzt.
  Im Code: `ClaimSource` und `ClaimTrust` in `contract/tool_api/claims/Claims.kt`; `requires` in
  `tools/auth_password/PasswordToolModule.kt`.
- **Identifizierungsmittel mit einem Bezeichner, der für das Mittel selbst eindeutig ist.** Der
  Online-Ausweis liefert eine Kennung, die nur für diesen Diensteanbieter gilt (`restrictedId`). Das
  Konto speichert sie als eigenen Anker. Über diesen Anker wird die Person wiedererkannt, nicht über
  Name und Geburtsdatum. Liest Nect die Karte, entsteht Nects eigenes Pseudonym als weiterer Anker.
  Das Pseudonym ist für jeden Diensteanbieter verschieden (§ 18 PAuswG). Deshalb erkennt jeder der
  beiden Wege die Karte nur für sich wieder. Eine neue Karte ersetzt den Wert.
  Reisepass und EUDI-Wallet liefern keinen solchen Bezeichner. Das steht in Abschnitt 4.
  Im Code: Anker `EID_RESTRICTED_ID` in `tools/ident_eid/EidToolModule.kt`, `NECT_RESTRICTED_ID` in
  `tools/ident_nect/NectToolModule.kt`; `tools/ident_nect/internal/IdentNectToolHandler.kt`. Mehr
  dazu in ADR-19.
- **E-Mail-Konto mit Einmalcode als Identifizierungsmittel.** Das Tool „E-Mail bestätigen“ prüft einen
  Code und speichert die Adresse als bestätigten Anker (Stufe *nachgewiesen*). Wie im Glossar
  vertraut der Server dabei stillschweigend darauf, dass der Nutzer sich bei seinem E-Mail-Anbieter
  angemeldet hat. Das Projekt nennt diesen Schritt Bestätigen, nicht Identifizierung, denn er ordnet
  keine Person zu und erhöht das Niveau nicht. Erst ein zweites Tool macht die bestätigte Adresse auf
  Wunsch zu einem Anmeldeverfahren.
  Im Code: `confirm-email` (Tool-Rolle `ATTESTATION`) und `enroll-email` in
  `tools/auth_email/EmailToolModule.kt`; `EMAIL_ANCHOR_ENROLLMENT` in
  `contract/tool_api/directory/AccountDirectory.kt`. Mehr dazu in ADR-17.
- **Identifizierung als Anreicherung eines schon wiedererkannten Clients, auch in einem späteren
  Schritt.** Online-Ausweis oder Nect stellen zuerst fest, wer jemand ist. Danach ordnet die Eingabe
  der KVNR diese Person einem Datensatz im Personenverzeichnis zu. Ein Konto, das zuerst nur ein
  Anmeldeverfahren einrichtet und sich erst später ausweist, folgt demselben Muster. Das entspricht
  dem Beispiel mit Bahnticket und Personalausweis im Glossar.
  In der Doku: [Verfahren `eid`](../verfahren/eid.md) und [`kvnr`](../verfahren/kvnr.md). Mehr dazu
  in ADR-18.
- **Biometrie am Beispiel des Ausweisfotos.** Nect mit Reisepass liest den Chip des Passes und
  vergleicht das Lichtbild mit dem Gesicht. Das ergibt Besitz und Biometrie. Diese Kombination
  verbindet das Dokument schwächer mit seinem Inhaber als die PIN des Personalausweises. Deshalb
  reicht sie nur für `loa2`. Der Dienstleister ist simuliert.
  Im Code: `tools/ident_nect/internal/IdentNectToolHandler.kt`.
- **ID-Server.** Orchestrator und Keycloak verwalten zusammen die Konten und ihre Attribute. In den
  Tokens geben sie diese zusammen mit dem Stand der Authentifizierung weiter, also mit dem Niveau
  (`acr`), den benutzten Verfahren (`amr`) und dem Zeitpunkt der Anmeldung. Welche Attribute in
  welchem Token stehen, ist bewusst getrennt:
  - Das **AccessToken** geht an jeden Fachdienst. Es enthält nur die beiden Attribute, die die
    Identität bezeugen: Partnernummer und Mitgliedsnummer.
  - Das **ID-Token** ist für die Anwendung, bei der sich der Nutzer angemeldet hat. Es enthält alle
    Attribute: zusätzlich Name, E-Mail-Adresse, KVNR, Geburtsdatum und Anschrift.

  Die Personen selbst verwaltet das Personenverzeichnis. Ändert es eine Person, meldet es das über
  ein Ereignis, und die Konten werden daraufhin angepasst. Keycloak hält keine eigene Kopie der
  Konten, sondern liest sie beim Orchestrator nach.
  Auch die Forderung des Glossars, bei Verwaltungsoperationen Sicherheitsanforderungen zu
  berücksichtigen, erfüllt das Projekt: Die eigenen Anmeldeverfahren verwalten und das Konto löschen
  verlangt `loa2`, bei einem nie identifizierten Konto `loa1`. Ein Niveau über
  `loa1` zählt dabei nur, wenn sein Nachweis höchstens 30 Minuten alt ist. Einen Anker ändern
  verlangt immer `loa2`.
  Im Code: `core/orchestrator/session/TokenService.kt` (Tokens ohne Keycloak);
  `core/account/application/PersonChangeListener.kt`;
  `core/orchestrator/domain/policy/DefaultAuthPolicy.kt`. In der Doku: [API](../05-api.md),
  Abschnitt 3a „ID-Token-Claims“ und Abschnitt 3b „Was in welchem Token steht“. Mehr dazu in ADR-34
  und ADR-38.
- **Authentifizierung über einen Dritten, dem der Server vertraut.** Das Glossar lässt zu, dass das
  Authentisierungsmittel nicht mit dem Server selbst, sondern mit einem vertrauten Dritten vereinbart
  wurde. Genau so funktionieren Freischaltcode und Vorgangszugang: Das Personenverzeichnis schickt
  einer bestimmten Person per Brief einen Code. Wer den Code kennt, besitzt den Brief. Das Projekt
  zählt das als Faktor Besitz. Beim Vorgangszugang entsteht dabei kein Konto. Angemeldet ist die
  Einladung, und die Tokens gelten nur für diesen Vorgang. Das Niveau legt die Einladung fest
  (`loa1` oder `loa2`).
  Im Code: `tools/auth_invite/InviteToolModule.kt`; `tools/ident_fsc/FscToolModule.kt`.
  In der Doku: [Verfahren `invite`](../verfahren/invite.md) und [`fsc`](../verfahren/fsc.md). Mehr
  dazu in ADR-31 und ADR-48.
- **Mehrstufige Authentifizierung gegenüber 2FA.** SMS und Passwort sind zwei Verfahren, die einzeln
  geprüft werden. Zusammen erreichen sie `loa2`. In der Doku heißt das mehrstufige
  Authentifizierung. NIST SP 800-63B stellt diese Kombination für AAL2 der MFA gleich. Deshalb
  ergibt sie dasselbe Niveau.
  In der Doku: [Orchestrierung](../04-orchestrierung.md), Abschnitt 4, „IAL und AAL“.

## 4) Wo das Projekt dem Glossar nur mit Einschränkung folgt

- **Challenge-Response.** Beim Geräteschlüssel stellt der Server keine eigene Challenge, also keine
  Zufallsaufgabe, die das Gerät beantworten muss. Stattdessen ist der signierte Geräte-Proof an genau
  diese eine Anfrage gebunden (Methode, Adresse, Zeitpunkt, einmalige Kennung) und gegen
  Wiederholung geschützt. Bei KOBIL fragt der Server die Bestätigung selbst beim Anbieter ab. Beides
  erfüllt denselben Zweck, ist aber kein Challenge-Response im engeren Sinn.
  In der Doku: [Verfahren `device`](../verfahren/device.md); [DPoP-Bindung](../09-dpop.md),
  Abschnitt 2. Mehr dazu in ADR-21.
- **Sicherer Speicher.** Die Schlüssel liegen im Browser. Sie werden über die Web Crypto API als
  nicht exportierbar erzeugt. Ihr Wert lässt sich über keine Programmierschnittstelle auslesen. Das
  kommt der Definition im Glossar nahe. Eine Garantie durch Hardware und eine Zertifizierung gibt es
  aber nicht. Das Geheimnis, mit dem KOBIL entsperrt wird, liegt sogar nur im lokalen Speicher des
  Browsers. Das Projekt ist eine Demo und sagt das an dieser Stelle ausdrücklich.
  Im Code: `frontend/src/deviceKey.ts`, `frontend/src/dpop.ts`, `frontend/src/kobilUnlockSecret.ts`.
  In der Doku: [DPoP-Bindung](../09-dpop.md), „Wie sicher die Schlüssel im Browser sind“.
- **Server, Client und Person.** Das Glossar setzt Client und Person teilweise gleich. Das Projekt
  trennt drei Dinge: das Gerät (erkannt an seinem Schlüssel), das Konto und die Person im
  Personenverzeichnis. Ein wiedererkanntes Gerät sagt nur, welches Gerät eine Anfrage schickt. Es
  sagt nicht, wer das Gerät benutzt. Das verfeinert das Glossar, ohne ihm zu widersprechen.
- **Versicherungs-Smartcard als Identifizierungsmittel.** Eine Gesundheitskarte gibt es im Projekt
  nicht. Die KVNR wird eingetippt, nicht bescheinigt. Deshalb darf die Eingabe erst stattfinden,
  wenn Name, Vorname und Geburtsdatum bereits bescheinigt sind. Zugeordnet wird nur, wenn die Person
  hinter der Nummer zu diesen Angaben passt. Für die Partnernummer gilt dasselbe.
  Im Code: `tools/ident_kvnr/KvnrToolModule.kt` (Tool-Rolle `CORRELATION`, `requires`);
  `core/account/application/IdentityMatchingService.kt`.
- **Unbescheinigte Attribute nie für die Zuordnung zu einem Stammdatensatz.** Beim Freischaltcode
  tippt die Person Nummer, Namen und Geburtsdatum ein. Das sind unbescheinigte Angaben. Sie dienen
  aber nur dazu, den Datensatz zu finden. Wofür das Personenverzeichnis verantwortlich ist, ist der
  Code aus dem Brief, den es selbst an diese Person geschickt hat. Das entspricht dem Einmalcode aus
  dem Glossar, einem schwächeren Verfahren ohne Kryptographie. Deshalb erreicht der Freischaltcode
  nur `loa2`, der Online-Ausweis dagegen `loa3`.
  Im Code: `tools/ident_fsc/internal/IdentFscToolHandler.kt`;
  `simulation/personenverzeichnis/Freischaltcodes.kt`.
  In der Doku: [Verfahren `fsc`](../verfahren/fsc.md).
- **SIM-Karte mit Telefonnummer als Identifizierungsmittel.** Die SMS dient nur als Anmeldeverfahren
  mit dem Faktor Besitz. Die Telefonnummer wird gespeichert, dient aber nie dazu, eine Person zu
  erkennen. Sie ist kein Anker, sondern gehört dem Modul des SMS-Verfahrens. Das Projekt nutzt die
  SIM-Karte bewusst nicht als Identifizierungsmittel.
  Im Code: `PHONE_NUMBER` in `tools/auth_sms/SmsToolModule.kt`.
- **Eindeutiger Bezeichner bei Reisepass und EUDI-Wallet.** Das Glossar verlangt von einem
  Identifizierungsmittel einen Bezeichner, der für die Person oder sogar für das Mittel eindeutig
  ist. Der Online-Ausweis liefert ihn (siehe Abschnitt 3). Beim Reisepass fordert das Projekt die
  Dokumentnummer bewusst nicht an (§ 20 PAuswG, § 16 PassG), und die EUDI-Wallet liefert kein
  Pseudonym. Beide bescheinigen also Attribute, ohne dass das Konto daraus einen Anker bekommt. Eine
  spätere Identifizierung mit demselben Dokument findet das Konto deshalb nicht von selbst wieder.
  Die Zuordnung zur Person läuft dann wie bei jeder Identifizierung über die KVNR. Ein Anker für den
  Reisepass ist noch offen.
  In der Doku: [Verfahren `nect`](../verfahren/nect.md).
- **Anmeldung mit der App bestätigen.** Der Browser hat dabei kein eigenes Authentisierungsmittel.
  Er übernimmt die Anmeldung der App: Die App muss in den letzten 30 Minuten selbst `loa2`
  nachgewiesen haben, und der Bestätigungscode, den der Nutzer von der App in den Browser
  überträgt, verbindet beide. Das Projekt meldet dafür Besitz und Wissen und höchstens `loa2`. Im
  Sinne des Glossars sind das aber keine zwei miteinander verknüpften Faktoren eines einzigen
  Authentisierungsmittels. Der Browser stützt sich auf die Authentifizierung eines anderen Geräts
  desselben Nutzers.
  Im Code: `tools/auth_qr/QrToolModule.kt`. In der Doku: [Verfahren `qr`](../verfahren/qr.md).

## 5) Was auf einer anderen Ebene liegt

- **Kommunikationspartner, sichere Kommunikation, sicherer Kommunikationskanal.** Das sind Begriffe
  der Transportschicht, also der Ebene, auf der Daten verschlüsselt übertragen werden. Das Projekt
  setzt HTTPS voraus und beschreibt es nicht als eigenen fachlichen Begriff.
- **Nachricht.** Am ehesten entspricht ihr der DPoP-Proof: Jede Anfrage enthält einen eigenen
  signierten Proof, der nur für sie gilt und sich nicht wiederholen lässt. Einen eigenen Begriff
  „Nachricht“ gibt es im Projekt nicht.
  In der Doku: [DPoP-Bindung](../09-dpop.md), Abschnitt 1.
- **Identität als eine Sammlung von Attributen.** Dazu der nächste Abschnitt.

## 6) Identität: eine Sammlung oder mehrere Bausteine

Das Glossar beschreibt Identität als **eine** Sache: eine Sammlung von Attributen, die einem Client
oder einer Person zugeordnet sind. Das Projekt verteilt dasselbe auf mehrere Bausteine, die
unterschiedlich lange bestehen. Keiner davon ist für sich allein die Identität im Sinne des
Glossars.

1. **Das Konto selbst enthält keine Attribute.** Es hat nur eine Nummer, einen Zeitpunkt der Anlage
   und eine Versionsnummer. Es ist der Bezugspunkt, auf den sich alle anderen Bausteine beziehen.
2. **Die Angaben (Claims) bilden die Geschichte.** Jede Änderung einer Angabe wird mit Quelle und
   Niveau als neuer Eintrag hinzugefügt und nie überschrieben. Die Stufe folgt aus der Quelle.
   Widerrufe nehmen Werte zurück, etwa wenn das Personenverzeichnis eine Mitgliedsnummer ändert.
3. **Die Anker sind der aktuelle Stand.** Partnernummer, Mitgliedsnummer, die Kennungen des
   Ausweises und die E-Mail-Adresse stehen je Konto in eigenen Zeilen, höchstens ein Wert je Art.
   Das ist ihr einziger Speicherort. Die Claims halten nur fest, wie die Anker entstanden sind.
4. **Stammdaten bleiben im Personenverzeichnis.** Name, Geburtsdatum, Anschrift und KVNR einer
   zugeordneten Person liest das Projekt immer aktuell von dort. Im Konto stehen sie nur als Teil der
   Geschichte. Ist dem Konto noch keine Person zugeordnet, gilt der stärkste bestätigte Wert aus den
   Angaben. Die Telefonnummer für die SMS speichert das Modul dieses Verfahrens.
5. **Die Rolle wird abgeleitet, nicht gespeichert.** Ob ein Konto einem Interessenten, Partner oder
   Versicherten gehört, folgt aus Partnernummer und Mitgliedsnummer. Ein Konto kann schon einen
   bestätigten Namen haben und trotzdem einem Interessenten gehören, solange ihm keine Person
   zugeordnet ist.
6. **Das Änderungsprotokoll ist ein Beleg, keine Identität.** Es hält unter anderem fest, dass und
   wie identifiziert wurde. Für Entscheidungen wird es nicht gelesen. Es bleibt auch erhalten, wenn
   das Konto gelöscht wird.

**Einordnung:** Der aktuelle Stand der Anker, ergänzt um die Stammdaten aus dem Personenverzeichnis,
entspricht zu jedem Zeitpunkt der Identität im Sinne des Glossars. Die übrigen Bausteine
beantworten Fragen, die das Glossar voraussetzt, aber nicht stellt:

- Woher kommt ein Wert?
- Seit wann gilt er als bescheinigt?
- Was geschieht, wenn zwei Bestätigungen einander widersprechen?

Das ist eine Verfeinerung, kein Widerspruch.

Im Code: `core/account/infrastructure/Account.kt`; `core/account/application/ClaimLedger.kt`;
`core/account/AccountService.kt`; `contract/tool_api/claims/AttributeType.kt`. In der Doku:
[Domänenmodell](../02-domaenenmodell.md), Abschnitt 6 „Konto-Identität: Claims, Anker,
Konsolidierung“. Das Änderungsprotokoll beschreibt ADR-39.

## 7) Bewusste Abweichungen und ihre Begründung

In den folgenden Punkten weicht das Projekt vom Glossar ab. Das Projekt hält diese Abweichungen für
richtig und möchte sie mit dem Autor des Glossars besprechen.

1. **Ein Konto ohne zugeordnete Person.** Das Glossar sagt nicht, ob die Zuordnung zu einer Person
   schon besteht. Das Projekt trennt zwei Fragen: „Kann sich dieses Gerät wieder anmelden?“ und
   „Wissen wir, wer das ist?“. Danach unterscheidet es noch, ob die Person bei der Versicherung
   versichert ist. Sobald eine Person zugeordnet ist, verhält sich das Konto so, wie das Glossar es
   beschreibt. Mehr dazu in ADR-10 und ADR-34.
2. **Claims mit Geschichte statt einer Sammlung von Attributen.** Der aktuelle Stand entspricht der
   Identität im Sinne des Glossars. Die Geschichte wird gebraucht, um später zu beantworten, warum
   ein Wert zu einem bestimmten Zeitpunkt als bescheinigt galt. Im Gesundheitswesen ist das ein
   Vorteil.
3. **Drei Stufen statt bescheinigt oder unbescheinigt.** Das Glossar deutet selbst an, dass es Wege
   unterschiedlicher Stärke gibt: Zertifizierung auf der einen Seite, weniger sichere Verfahren ohne
   Kryptographie auf der anderen. Das Projekt macht diese Abstufung ausdrücklich und prüft sie
   automatisch.
4. **Konten werden über Anker gefunden, nie über Namen, und nie selbsttätig zusammengelegt.** Nur ein
   verwerfbares Konto wird mit dem gefundenen Konto zusammengeführt. Verwerfbar heißt: ohne
   zugeordnete Person und ohne ein jemals eingerichtetes Verfahren. Das Glossar beschreibt die
   Identifizierung als Ergebnis. Das Projekt beschreibt auch den Vorgang selbst: was geschieht, wenn
   eine neue Bestätigung auf ein bestehendes Konto trifft. Mehr dazu in ADR-11, ADR-19 und ADR-20.
5. **Faktortypen je Nachweis statt fest je Verfahren.** Das entspricht dem Glossar (siehe
   Abschnitt 3). Das Projekt geht nur in der Genauigkeit darüber hinaus.
6. **Vertrauen in die Freigabe auf dem Gerät.** Geräteschlüssel und KOBIL melden Wissen oder
   Biometrie, die der Server nicht sieht. Das ist genau das Vertrauen in das Gerät, das das Glossar
   bei der Gerätebindung selbst verlangt. Solange der Server die Freigabe nicht prüfen kann, läuft
   das nur im Demomodus. Mehr dazu in ADR-21 und ADR-36.
7. **Freischaltcode trotz eingetippter Angaben.** Die eingetippten Angaben finden nur den Datensatz.
   Bescheinigt wird durch den Code aus dem Brief. Dass das schwächer ist als der Online-Ausweis,
   zeigt das niedrigere Niveau.

Die Entscheidungen hinter den genannten ADR-Nummern stehen in den
[Entscheidungen](../12-entscheidungen.md).
