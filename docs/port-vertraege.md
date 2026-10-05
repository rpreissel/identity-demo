# Port-Verträge der Fremdsysteme

Der Kern dieses Projekts, also das Backend mit dem Orchestrator und den Tool-Modulen, fragt
Fremdsysteme über Ports. Ein **Port** ist eine fest vereinbarte Schnittstelle zu einem System
außerhalb des Kerns, etwa für die Frage „Ist dieser Freischaltcode gültig?“
([Glossar](glossar/glossar.md)). Dieses Dokument beschreibt für jedes dieser Systeme, was ein
**echtes** System zusagen muss, damit der Kern ihm so vertrauen darf, wie er es heute tut
([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).

In dieser Instanz sind alle Fremdsysteme simuliert. Die Simulationen bilden die Sicherheit eines
echten Systems nicht nach. Der Vertrag hier sagt deshalb, was man prüfen muss, wenn man das echte
System anschließt.

Manche Verfahren erreichen ihr Niveau, also das Maß an Vertrauen in eine Anmeldung, allein durch
ein Fremdsystem. Ist dieses Fremdsystem simuliert, ist das Verfahren als `demoOnly` gekennzeichnet.
Außerhalb des Demomodus, also im echten Betrieb, ist ein solches Verfahren abgeschaltet
([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)).

Für jeden Vertrag gilt: Der Kern entscheidet nur auf Grundlage dessen, was die Antwort des Systems
zusagt. Er leitet nichts aus Angaben des Clients ab, die eigentlich das System hätte prüfen müssen.

---

## Personenverzeichnis (`PersonDirectory`, `PersonMasterData`, `ActivationCodes`, `Invitations`)

Das Personenverzeichnis enthält die Stammdaten der Versicherung: Personen mit Partnernummer, Name,
Geburtsdatum, Anschrift und, bei Versicherten, Mitgliedsnummer und KVNR (Krankenversichertennummer).
Es wird genutzt von `ident-fsc`, `ident-kvnr`, `auth-invite-lookup`, vom Abgleich jeder
Identifizierung und von den Token-Claims in Keycloak, also den Angaben, die Keycloak in das Token
schreibt. Dieser Vertrag ist **nicht** `demoOnly`: Mit `demo.mode=false` ist das Personenverzeichnis
der einzige Weg zu einer Identifizierung. Deshalb ist dieser Vertrag der wichtigste.

- **Suche nur über Kennungen.** KVNR und Partnernummer führen zur Partnernummer. Stammdaten gehen
  nie über den Port. Der Port beantwortet nur die Frage „Passen diese Angaben?“ (`matchesMasterData`,
  `matchesPersonalDetails`). Ausdrücklich ausgenommen sind der Anzeigename und die Mitgliedsnummer
  (ADR-34). Die Stammdaten selbst liefert nur ein eigener Port, `PersonMasterData`, und auch der nur
  für die Claims, die Keycloak ins Token schreibt
  ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)).
- **Namensvergleich in Ausweisform** (MRZ, die maschinenlesbare Zone eines Ausweises): Groß- und
  Kleinschreibung, die Schreibung von Umlauten und Diakritika zählen beim Vergleich nicht. Ein
  echtes System muss genauso vergleichen, sonst scheitern echte Personen an der Identifizierung.
- **Freischaltcode** (ADR-31, Port `ActivationCodes`): Ein Freischaltcode ist ein Code, mit dem
  sich eine Person identifizieren kann. Für ihn gilt:
  - Nur das Verzeichnis gibt ihn aus, per Post an die hinterlegte Anschrift.
  - Er wird nie im Klartext gespeichert, sondern als Hash mit Pepper (einem geheimen Zusatzwert).
  - Er hat ein Ablaufdatum und lässt sich widerrufen.
  - Bis zum Ablauf ist er bewusst mehrfach verwendbar, damit sich eine Person erneut identifizieren
    kann (ADR-31).
  - Wie lang der Code ist und wie schwer er sich damit erraten lässt, bestimmt das System. Der Kern
    sperrt die Person nach zu vielen Fehlversuchen (`PersonLockoutService`).
- **Einladungen mit Einmalkennwort**
  ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)): Eine Einladung ist ein Brief, der
  eine Person zu einem bestimmten Vorgang einlädt. Für sie gilt:
  - Nur das Verzeichnis stellt sie aus, und zwar für eine Person, einen Vorgang, ein Niveau
    (`loa1`/`loa2`) und eine Frist. Das Kennwort kommt per Post.
  - Die Id der Einladung ist ein SHA-256-Hash über `personId:KENNWORT:vorgang`. Das Kennwort steht
    darin ohne Trennzeichen und in Großbuchstaben. So kann das Fachsystem die Id selbst berechnen.
    Gespeichert wird nur diese Id.
  - `redeem` findet nur eine offene Einladung genau dieser Person, nie eine Einladung allein über
    das Kennwort.
  - Abschluss und Widerruf einer Einladung meldet das System als `InvitationEnded`, mindestens
    einmal. Der Kern beendet daraufhin die Keycloak-Sitzungen der Einladung.
  - Ein echtes System braucht dafür eine eigene Schnittstelle zum Fachsystem. Die Demo zeigt diese
    Schnittstelle als Seite „Einladungen“ im Personenverzeichnis.
- **Änderungen werden gemeldet** (ADR-34): Das System meldet jede Änderung an einer Person. Der Kern
  verarbeitet diese Meldungen nacheinander, nie parallel (`PersonChangeListener`). Ein echtes System
  muss jede Meldung mindestens einmal zustellen und die Reihenfolge je Person einhalten.
  E-Mail-Adresse und Mobilnummer meldet es nicht. Für diese beiden Werte führt das Konto eigene, vom
  Nutzer bestätigte Werte, die vom Verzeichnis abweichen dürfen.
- **Nur in der Demo:** Ein eigener Port, `DemoPersonDirectory`, liefert alle Personen für die
  Auswahl „Testperson übernehmen“ (ADR-28). Zu jeder Person gehören E-Mail-Adresse, Mobilnummer und
  der Klartext des jüngsten gültigen Freischaltcodes. Dazu liefert der Port die offenen Einladungen
  mit ihrem Kennwort für die Auswahl „Einladung übernehmen“. Mit diesen Werten füllt die Demo nur
  Formulare vor. Ins Konto gelangen E-Mail-Adresse und Mobilnummer erst, wenn der Nutzer sie per Code
  bzw. TAN bestätigt hat. Ein echtes System muss diesen Port nicht anbieten.

## KOBIL (`kobil.KobilSsms`) – `demoOnly`

KOBIL ist ein externer Anbieter einer App, die als Anmeldeverfahren an ein Smartphone gebunden ist.
Genutzt wird der Port von `enroll-kobil` und `auth-kobil` (loa2).

- **Das Ergebnis kommt vom Server.** `verifyOtp` löst ein Einmalpasswort (OTP) beim KOBIL-Server
  ein. Es zählt nur die Antwort dieses Servers (Gerät, Risikosignale), nie eine Angabe der App.
- **Einmaligkeit:** Ein OTP gilt genau einmal und nur kurz. Wird es ein zweites Mal eingelöst,
  liefert der Server nichts.
- **Nutzerverifikation am Gerät:** PIN bzw. Biometrie prüft KOBIL auf dem Gerät und bestätigt das
  Ergebnis mit einer Signatur. Ohne diese Zusage wäre `auth-kobil` kein zweiter Faktor.
- **Risikosignale** (etwa ein gerootetes Gerät) meldet KOBIL. Der Kern lehnt dann ab.
- **Aktivierungsgeheimnisse** gelten nur kurz und werden nicht noch einmal ausgeliefert. In dieser
  Instanz liegen sie 24 Stunden im Klartext in der ToolSession; diese Zeit ist beim Anschluss eines
  echten Systems zu kürzen.

## Nect (`nect.NectIdent`) – `demoOnly`

Nect ist ein externer Dienst, der Personen identifiziert, etwa mit Personalausweis, Reisepass oder
EUDI-Wallet. Der Nutzer wird dafür zu Nect weitergeleitet und kommt danach zurück. Genutzt wird der
Port von `ident-nect` (loa3).

- **Das Ergebnis holt der Server ab** (`redeem` über die Fall-ID), nie aus dem Rücksprung des
  Browsers. Der Rücksprung sagt nur „fertig“, nicht „wer“.
- **Einmal einlösbar:** Ein Fall liefert sein Ergebnis genau einmal.
- **Bindung an den Vorgang:** Der Fall gehört zu genau diesem Ablauf (Callback-URI). Einen fremden
  Fall lehnt der Kern ab. Im Einzelnen:
  - Die Callback-URI nennt der Kanal beim Start. Im Web-Kanal ist das Keycloaks Action-URL des
    laufenden Schritts.
  - `ident-nect` nimmt nur Adressen unter einem konfigurierten Präfix an.
  - Ein `retry` behält die Adresse, außer der Kanal nennt eine neue. Im Web-Kanal ist das nötig,
    weil Keycloaks Code nur einmal gilt (ADR-47).
  - Nect fügt `nectCaseId` auch an eine Adresse an, die schon Parameter enthält.
- **Nur angefragte Attribute** werden geliefert. Die Echtheit von Dokument und Person prüft Nect
  (Selfie gegen Passbild, Ablaufdatum des Passes) und meldet das Ergebnis.

## eID-Server (in `ident_eid` simuliert) – `demoOnly`

Der eID-Server prüft die Ausweisfunktion des Personalausweises (Online-Ausweis). Genutzt wird er
von `ident-eid` (loa3).

- **Das Ergebnis kommt auf der Serverseite vom eID-Server**, nachdem dieser selbst Karte und PIN
  geprüft hat. Es stammt nie aus Angaben des Clients. In dieser Instanz simuliert `ident_eid` das
  Auslesen der Karte selbst.
- **`restricted_id`** (das Sperrmerkmal je Dienstanbieter) ist der Anker
  ([ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md)). Ein Anker ist eine
  Angabe, über die sich ein Konto eindeutig wiederfinden lässt. Die `restricted_id` bleibt für
  dieselbe Karte und denselben Anbieter immer gleich.
- **Keine Personenkennung auf der Karte:** Der Abgleich mit dem Personenverzeichnis läuft über die
  Ausweisdaten (`matchesMasterData`), nicht über eine Nummer.

## Zustellung von TAN und Code (`sms`, `mail`)

Genutzt von `enroll-sms`, `auth-sms`, `confirm-email`, `auth-email` und den `-lookup`-Varianten.

- **Zustellung nur an die hinterlegte Adresse.** Der Inhalt darf dabei nicht in Logs gelangen. Der
  Kern schreibt TAN und Code nie auf die Konsole ([Invarianten](invarianten.md) I-19).
- **Mengenbegrenzung:** Wie viele Nachrichten ein Empfänger bekommen darf, begrenzt das Modul, das
  sie versendet (`SmsSendLimit`, `EmailSendLimit`,
  [ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)). Ein echter Dienst darf
  zusätzlich begrenzen. Er muss dann aber melden, dass er einen Versand abgelehnt hat.
- Diese Verfahren sind nicht `demoOnly`. Was sie beweisen, also den Besitz der Nummer bzw. des
  Postfachs, prüft der Kern selbst über den zugestellten Code.
