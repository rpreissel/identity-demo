# Beispiel: Mara registriert sich, kommt wieder, hebt ihr Niveau an

Dieses Kapitel erzählt ein einziges, durchgehendes Beispiel. Es zeigt an einer Person, was die
Grundbegriffe des Projekts bedeuten: Kanal, Journey, Tool, `next`, Niveau und DPoP. Jeder Begriff
wird erklärt, wenn er zum ersten Mal vorkommt. Das Beispiel ist bewusst knapp und nicht vollständig.
Für jeden Schritt nennt der Text das Kapitel, das ihn genau beschreibt.

---

## 1) Mara installiert die App

Mara öffnet die App zum ersten Mal. Die App erzeugt auf dem Gerät ein Schlüsselpaar. Dafür nutzt
sie die Web Crypto API des Browsers. Der private Schlüssel lässt sich nicht aus dem Gerät
exportieren.

Ihre erste Anfrage an den Server schickt die App mit einem `DPoP`-Proof statt mit einem Passwort
oder Zertifikat. Ein DPoP-Proof ist ein kurzer Beleg, den die App mit ihrem privaten Schlüssel
unterschreibt. Er beweist, dass die Anfrage vom Besitzer dieses Schlüssels kommt.

Das Backend, genauer der **Orchestrator**, legt daraufhin einen **Kanal** an
(`ChannelSession(APP)`). Ein Kanal ist die Verbindung der App zum Orchestrator für diese Nutzung. Er
ist an den Fingerabdruck von Maras Schlüssel gebunden (`binding_key_ref`). Noch hat der Kanal den
Zustand `ANONYMOUS` (anonym), denn es gibt kein Konto, das zu diesem Gerät gehört.

Dieser Schlüssel ist noch **kein Anmeldeverfahren**. Er beweist, dass zwei Anfragen vom selben Gerät
kommen. Er beweist aber nicht, wer Mara ist. Ihr Gerät als Anmeldeverfahren richtet sie erst in
Abschnitt 6 ein, und das ist ein anderer Vorgang.

*Konzepte: [`ChannelSession`](02-domaenenmodell.md), [DPoP-Proof](09-dpop.md).*

## 2) Mara registriert sich

Die App startet eine **Journey** mit dem **Intent** `REGISTER`. Eine Journey ist ein geführter
Ablauf aus mehreren Schritten. Der Intent sagt, was der Nutzer erreichen will. Hier drückt er Maras
Wunsch aus, sich neu auszuweisen. Er beschreibt also nicht nur den technischen Ablauf dahinter.

Der Orchestrator bietet zuerst die Verfahren zur Identifizierung an. Jedes davon ist ein **Tool**,
also ein einzelner Arbeitsschritt: `ident-fsc` (Freischaltcode), `ident-eid` (Online-Ausweis) und
`ident-nect` (Nect). Mara wählt `ident-fsc`. Das ist der Freischaltcode, den ihr das
Personenverzeichnis per Brief geschickt hat. Das Personenverzeichnis ist das System der Versicherung
mit den Stammdaten aller Personen.

Die App weiß nicht von selbst, was jetzt an der Reihe ist. Sie folgt nur `next`. Das ist eine reine
Adresse in der Antwort des Backends. Über eine feste Tabelle sucht die App dazu die passende
Komponente der Oberfläche heraus.

Mara gibt ihre Versichertennummer (KVNR), Namen, Vornamen und Geburtsdatum ein. Danach gibt sie den
Code aus dem Brief ein. `ident-fsc` ist ein eigenes Tool-Modul. Es prüft die Angaben gegen das
Personenverzeichnis und den Code gegen die dort ausgestellten Freischaltcodes.

Dem Orchestrator meldet das Tool ein Ergebnis (`ToolOutcome.Completed.Identified`). Es enthält
Maras Partnernummer als `PERSON_ID`-Claim und die geprüften Angaben. Ein Claim ist eine Angabe über
eine Person, zusammen mit der Quelle, die sie bestätigt. Hier ist die Quelle das
Personenverzeichnis, nicht das Tool.

Der Orchestrator legt daraufhin ein neues Konto an. Er speichert Maras `person_id` als **Anker**.
Ein Anker ist ein Merkmal, über das sich das Konto später eindeutig wiederfinden lässt. Weil Mara
bei dieser Versicherung versichert ist, wird auch ihre Mitgliedsnummer ein Anker. Die Sitzung hat
damit das **Niveau** `loa2`. Das Niveau (Sicherheitsniveau) sagt, wie sehr das System einer
Anmeldung vertraut, von `loa1` bis `loa3`. Der Freischaltcode erreicht `loa2` allein.

Fertig ist die Registrierung damit nicht. Eine Identifizierung ist kein Anmeldeverfahren. Sie sagt,
**wer** jemand ist, aber nicht, **womit** er sich beim nächsten Mal wieder anmeldet. Es folgen zwei
Pflichtschritte:

1. **Adresse bestätigen** (`confirm-email`). Die bestätigte E-Mail-Adresse gehört zur
   Grundausstattung des Kontos, ist aber kein Anmeldeverfahren. Drei Arten der Anmeldung über die
   E-Mail-Adresse finden das Konto über diese Adresse. Außerdem setzt `enroll-password` sie voraus.
   Mara bekommt einen Code und gibt ihn ein.
2. **Anmeldeverfahren einrichten.** Mara wählt SMS (`enroll-sms`) und bekommt eine TAN. Damit
   könnte sie sich anmelden, aber nur auf `loa1`. SMS beweist nur, dass sie das Telefon besitzt,
   und reicht deshalb höchstens für `loa1`. Ein Konto, das nur `loa1` erreicht, könnte seine eigenen
   Einstellungen nie wieder verwalten, denn dafür ist `loa2` nötig. Deshalb verlangt die Journey ein
   zweites Verfahren **anderer Art**. Zur Wahl stehen Passwort, Gerätebindung und KOBIL. Mara nimmt
   das Passwort (`enroll-password`). Ein Passwort ist Wissen. Erst Besitz und Wissen zusammen
   erreichen `loa2`.

Beide Verfahren übernehmen das Niveau, das die Sitzung beim Einrichten nachgewiesen hatte. Hier ist
das die `loa2` der Identifizierung. Dieses Niveau bleibt dauerhaft bei den Verfahren gespeichert
(`enrolledUnderAcr`). Es begrenzt später, was ihre Kombination höchstens erreichen kann. Der Grund
steht in ADR-5: Niemand soll sich selbst höher einstufen können.

Hätte Mara statt Passwort und SMS gleich ihr Gerät eingerichtet, wäre der Schritt mit dem Passwort
entfallen. `enroll-device` deckt Besitz und Wissen (oder Biometrie) schon allein ab.

*Konzepte: [`AuthIntent`/Journey](04-orchestrierung.md) Abschnitte 2 und 3, [Tool-Vertrag](03-tool-architektur.md),
[`next`/`stepData`](05-api.md), [Registrierung im Detail](verfahren/README.md),
[Adresse ≠ Anmeldeverfahren, ADR-17](12-entscheidungen.md).*

## 3) Mara bekommt ihr AccessToken

Sobald die Anmeldung abgeschlossen ist, lässt der Orchestrator auf dem Server ein `AccessToken` von
**Keycloak** ausstellen. Keycloak ist das Produkt, das in diesem Projekt die Tokens ausstellt. Ein
`AccessToken` ist ein signierter digitaler Ausweis, der nur kurz gilt. Das Ausstellen läuft als
gewöhnlicher Ablauf nach dem Standard OIDC (OpenID Connect). Mara sieht davon nichts, nur das
Ergebnis.

Der Zustand ihres Kanals (`ChannelSession.state`) wechselt auf `AUTHENTICATED` (angemeldet).
Zugleich entsteht ihre Keycloak-Sitzung. Ihr Kanal lebt ab jetzt genau so lange wie diese Sitzung.
Wie lange das ist, legt Keycloak fest
([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)).

Ab jetzt ruft die App mit diesem Token die eigentlichen Fachdienste der Versicherung auf, also
andere Microservices. Sie ruft sie **direkt** auf und braucht den Orchestrator dafür nie wieder.
Registrierung und Anmeldung waren nur die Voraussetzung dafür, nie der eigentliche Zweck.

*Konzepte: [Tokenfluss](01-ueberblick.md), [ADR-9](12-entscheidungen.md).*

## 4) Mara kommt Wochen später wieder

Mara öffnet die App erneut, auf demselben Gerät. Als sie ihre Verfahren eingerichtet hat, wurde ihr
Konto mit diesem Gerät verknüpft (`DeviceAccountLink`). Diese Verknüpfung dient nur der
**Wiedererkennung**. Der Orchestrator weiß dadurch, um welches Konto es geht, bevor Mara irgendetwas
bewiesen hat. Ein Nachweis ist die Verknüpfung nicht.

Der Intent heißt jetzt `FAST_ACCESS`. Er bedeutet: möglichst bequem anmelden, mit Ausweichwegen,
statt sich wieder ganz neu auszuweisen. Hat das Konto ein Verfahren, das an das Gerät gebunden ist,
bietet der Orchestrator dieses zuerst an. Mara hat noch keines. Also bekommt sie die Wahl zwischen
SMS und Passwort. Sie nimmt die SMS und ist angemeldet, auf `loa1`: ein Verfahren, ein Niveau.

Lehnt sie ein angebotenes Verfahren ab, bietet die Journey die nächste Möglichkeit an. Anders als bei
`REGISTER` ist hier kein Schritt *Pflicht*. Es zählt nur das Niveau, das am Ende tatsächlich
erreicht ist.

*Konzepte: [Ausweichweg vs. Pflicht](04-orchestrierung.md) Einstieg für Fachexperten,
[`FAST_ACCESS`](journeys/fast-access.md), [Bindung an die ChannelSession](09-dpop.md) Abschnitt 3.*

## 5) Mara will ihre Anmeldeverfahren verwalten

Mara möchte ein zweites Verfahren hinzufügen. Dafür gibt es die Journey `MANAGE_AUTH_METHODS`. Sie
setzt einen Kanal voraus, der bereits `AUTHENTICATED` ist. Zusätzlich verlangt sie `loa2` **in
dieser Sitzung**. Mara ist aber nur mit ihrer SMS-TAN angemeldet, also auf `loa1`.

Der Orchestrator verlangt deshalb einen `STEP_UP`. Ein Step-up ist ein zusätzlicher Nachweis, der
das Niveau der laufenden Sitzung erhöht. Mara hat zwei Möglichkeiten dafür:

- **Ein zweites Verfahren.** Mara gibt zusätzlich ihr Passwort ein. Zwei verschiedene Verfahren mit
  zwei verschiedenen Faktortypen (Besitz und Wissen) erhöhen das Niveau um eins. Ein **Faktortyp**
  ist die Art eines Beweises: etwas, das man besitzt, etwas, das man weiß, oder etwas, das man ist
  (Biometrie). Begrenzt ist das Niveau durch das Niveau, auf dem die Verfahren selbst eingerichtet
  wurden. Beide sind in einer Sitzung mit `loa2` entstanden, deshalb reicht es.
- **Erneut identifizieren.** `ident-fsc` erreicht `loa2` allein, ohne Kombination. Diesen Weg
  brauchen Konten mit nur einem einzigen Verfahren. Ohne ihn könnten sie ihre eigenen Einstellungen
  nie wieder verwalten.

Erst danach nimmt die Journey die Änderung an.

*Konzepte: [Sub-Journey `STEP_UP`](journeys/step-up.md),
[Begrenzung des Niveaus, ADR-5](12-entscheidungen.md).*

## 6) Mara macht ihr Gerät zum Anmeldeverfahren

Jetzt, auf `loa2`, richtet Mara zwei Dinge ein.

**`enroll-device`**: Die App erzeugt einen zweiten Schlüssel, nur für dieses Konto. Er liegt im
sicheren Speicher des Geräts, und Mara schaltet ihn mit Face ID frei. Gespeichert wird nur der
öffentliche Teil des Schlüssels. Der private Teil verlässt das Gerät nie.

Anders als die Wiedererkennung des Geräts aus Abschnitt 4 ist das ein echtes Anmeldeverfahren. Es
weist Besitz **und** Wissen bzw. Biometrie nach und erreicht damit allein `loa2`. Ab jetzt bietet
`FAST_ACCESS` ihr genau dieses Verfahren zuerst an. Mara nimmt das Gerät, schaut kurz hinein und ist
angemeldet, ohne SMS und ohne Passwort.

**`enroll-qr`**: eine reine Zustimmung ohne Geheimnis (`factorTypes = {}`). Sie sagt nur: „Dieses
Konto darf Anmeldungen auf anderen Geräten per QR-Code bestätigen.“ Das System prüft diese
Zustimmung dort, wo sie gebraucht wird, nämlich beim Bestätigen in Abschnitt 7. Ohne sie bestätigt
die App nichts.

*Konzepte: [`MANAGE_AUTH_METHODS`](journeys/manage-auth-methods.md),
[`factorTypes`/Tool-Katalog](03-tool-architektur.md), [Verfahren einrichten](verfahren/README.md).*

## 7) Mara meldet sich am Laptop an, mit dem Handy

Am Abend will Mara sich am Laptop im Kundenportal anmelden. Der Browser nutzt nicht den App-Kanal,
sondern den Web-Kanal. Dort führt Keycloak die Anmeldung und fragt den Orchestrator nach den
Schritten. Es gibt dieselben Journeys und dieselben Tools wie in der App. Es gibt nur zwei
Unterschiede: Keycloak zeigt die Oberfläche statt der App. Und die Anfragen sind durch eine Sitzung
abgesichert statt durch einen DPoP-Proof.

Das Kundenportal bietet schon auf der ersten Anmeldeseite alle Verfahren an
([ADR-42](adr/ADR-042-loa1-anmeldung-umschalten.md)). Mara wählt den QR-Code. Der Browser zeigt den
Code (`auth-qr-lookup`) und wartet. Wer Mara ist, weiß dieser Kanal noch nicht. Das stellt sich erst
heraus, wenn das Handy zustimmt.

Mara scannt den Code mit ihrer App. Dort startet die Journey `CONFIRM_PEER_LOGIN` (Anmeldung auf
einem anderen Gerät bestätigen). Bevor Mara zustimmen darf, verlangt der Orchestrator zwei Dinge von
der Sitzung in der **App**:

1. Die Sitzung muss selbst `loa2` erreichen. Eine Sitzung auf `loa1` darf keine Anmeldung auf einem
   anderen Gerät bestätigen. Mara erledigt das mit einem Blick in die Kamera, denn ihr
   Geräteverfahren aus Abschnitt 6 erreicht `loa2`.
2. Sie muss **neu** beweisen, dass gerade jetzt Mara am Gerät ist. Ein alter Nachweis von heute
   Morgen reicht für die Zustimmung nicht.

Erst dann erscheint `approve-qr`. Mara tippt auf „Bestätigen“, und der Browser ist angemeldet. Er
hat das Niveau `loa2`, obwohl dort nie ein Passwort eingegeben wurde. Das Niveau entsteht dabei
nicht ohne Nachweis: `auth-qr` darf es nur melden, weil die App vorher selbst `loa2` erreichen
musste. Die Anmeldung per QR-Code gibt nur weiter, was in der App schon bewiesen wurde.

*Konzepte: [`CONFIRM_PEER_LOGIN`](journeys/confirm-peer-login.md),
[Web-Zugang über Keycloak](05-api.md) Abschnitt 3b, [`PEER_APPROVAL` als eigene Rolle](03-tool-architektur.md).*

## 8) Maras Vater beantwortet einen Brief

Ein paar Tage später bekommt Maras Vater einen Brief der Kasse. Er bekommt Beiträge zurück und soll
dafür online seine Bankverbindung angeben. Ein Konto hat er nicht, und er will auch keines. Der
Brief enthält deshalb ein **Einmalkennwort**. Ausgestellt hat es das Personenverzeichnis, nicht der
Orchestrator, und zwar für genau diesen einen Vorgang
([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).

„Einmal“ heißt dabei „für einen Vorgang“, nicht „für eine einzige Anmeldung“. Das Kennwort gilt, bis
seine Frist abläuft oder die Kasse den Vorgang abschließt. Wird er heute nicht fertig, kann er
morgen wiederkommen.

Er setzt sich an Maras Laptop. Dort ist Mara aus Abschnitt 7 noch angemeldet, und das ist wichtig:
In einem Browser gehört eine Keycloak-Sitzung genau einer Person. Würde er jetzt die Vorgangsseite
öffnen, bekäme sie ohne Rückfrage Maras Token, ohne Hinweis auf den Vorgang. Mara meldet sich
deshalb zuerst ab. Ein Wechsel zwischen Konto und Vorgangszugang geht in beiden Richtungen nur über
die Abmeldung.

Im Kundenportal wählt er „Mit Einmalkennwort anmelden“. Auf der Anmeldeseite gibt er seine
Versichertennummer und das Kennwort aus dem Brief ein. Das Tool `auth-invite-lookup` prüft beides
beim Personenverzeichnis, so wie `ident-fsc` den Freischaltcode prüft. Sein Ergebnis nennt als
Subjekt aber **kein Konto, sondern die Einladung** (`Subject.Invitation`). Das Subjekt ist das, für
das die Anmeldung gilt.

Der Orchestrator legt deshalb kein Konto an und sucht auch keines. Der Web-Kanal gehört jetzt der
Einladung. Er hat das Niveau, das die Kasse für diesen Vorgang festgelegt hat, hier `loa1`. Verlangt
eine Seite später mehr, wird der Vorgangszugang nicht aufgewertet. Dafür bräuchte er eine Einladung
mit höherem Niveau.

Keycloak meldet ihn als eigenen Nutzer an, getrennt von jedem Konto. Das gilt auch für ein Konto, das
er vielleicht später anlegt. Seine Tokens sehen aus wie die eines Kontos, enthalten aber zusätzlich
den Claim `process`. Daran erkennt jeder Fachdienst: Dieses Token gilt nur für die
Beitragsrückerstattung.

Alles, was ein Konto betrifft, lehnt der Orchestrator ab:

- Verfahren verwalten,
- das Konto löschen,
- eine Anmeldung per QR-Code bestätigen.

Hat die Kasse den Vorgang abgeschlossen, meldet das Personenverzeichnis das dem Orchestrator. Die
Sitzungen des Vaters enden dann sofort.

Die App kann das noch nicht: Einen Vorgangszugang gibt es vorerst nur im Web-Kanal.

*Konzepte: [Vorgangszugang, ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md),
[Freischaltcode im Fremdsystem, ADR-31](adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md),
[Web-Zugang über Keycloak](05-api.md) Abschnitt 3b.*

## 9) Mara löscht ihr Konto

Ein Jahr später will Mara ihr Konto endgültig löschen. Die Journey `DELETE_ACCOUNT` verlangt zuerst
eine Bestätigung mit Ja oder Nein. Danach verlangt sie `loa2` **und** einen neu erbrachten Nachweis.
Ein alter Nachweis aus dem laufenden Kanal genügt nicht. Das ist dieselbe Vorsicht wie bei der
Anmeldung per QR-Code.

Erst wenn beides erfüllt ist, löscht der Orchestrator Maras Konto. Er löscht dabei alle Anker,
Verfahren, Verknüpfungen zu Geräten und ihren Journey-Trace. Der Journey-Trace ist die Aufzeichnung
aller Schritte ihrer Journeys. Der Orchestrator meldet außerdem alle ihre Sitzungen ab. Er löscht
auch die Daten, die Keycloak selbst zu ihrem Konto hält, etwa dessen Sitzungen.

Bis dahin ließ sich jeder Schritt im Journey-Trace nachvollziehen: welches Verfahren wann angeboten,
angenommen oder abgelehnt wurde. Niemand musste dafür verteilte Logs mehrerer Systeme
zusammensuchen. Nach der Löschung bleibt davon bewusst nichts übrig. Nur das Änderungsprotokoll hält
ohne Werte fest, dass es das Konto gab und wann es gelöscht wurde
([ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)).

*Konzepte: [`DELETE_ACCOUNT`](journeys/delete-account.md), [Journey-Trace](04-orchestrierung.md).*

---

## Maras Verfahren und was sie erreichen

Die Tabelle fasst zusammen, welche Verfahren im Beispiel vorkommen und welches Niveau jedes allein
erreicht.

| Verfahren | Faktortyp | Erreicht allein | Kommt vor in |
|---|---|---|---|
| `ident-fsc` (Freischaltcode per Brief) | Identifizierung, kein Anmeldeverfahren | `loa2` | Abschnitt 2, erneut in 5 |
| bestätigte Adresse | keiner, gehört zur Grundausstattung des Kontos | — | Abschnitt 2 |
| `sms` | Besitz | `loa1` | Abschnitt 2 |
| `password` | Wissen | `loa1` | Abschnitt 2 |
| `sms` + `password` zusammen | Besitz + Wissen | `loa2` | Abschnitt 5 |
| `device` | Besitz + Wissen/Biometrie | `loa2` | Abschnitt 6 |
| `qr` | Besitz + Wissen (aus der App übernommen) | `loa2` | Abschnitt 6/7 |
| `auth-invite-lookup` (Einmalkennwort per Brief, ihr Vater) | Besitz, kein Konto | das Niveau der Einladung (`loa1` oder `loa2`) | Abschnitt 8 |
| DPoP-Schlüssel / `DeviceAccountLink` | keiner, nur Wiedererkennung | — | Abschnitt 1/4 |

## Welche Begriffe das Beispiel verbindet

Die Tabelle ordnet jeder Situation aus dem Beispiel den Begriff des Projekts zu und nennt das
Kapitel, das ihn erklärt.

| Im Beispiel | Begriff | Im Code |
|---|---|---|
| Maras Verbindung zur App während einer Nutzung (das Gerät selbst merkt sich `DeviceAccountLink`) | `ChannelSession` | [02-domaenenmodell.md](02-domaenenmodell.md) |
| „Ich will mich registrieren“ / „Ich will mich nur schnell anmelden“ | `AuthIntent` (`REGISTER`, `FAST_ACCESS`, …) | [04-orchestrierung.md](04-orchestrierung.md) |
| Die eine Eingabe des Freischaltcodes, die eine Eingabe der TAN | `Tool` (`ident-fsc`, `enroll-sms`, …) | [03-tool-architektur.md](03-tool-architektur.md) |
| „Was soll die App jetzt anzeigen?“ | `next`/`stepData` | [05-api.md](05-api.md) |
| „Reicht das schon für die Anmeldung oder für diese Aktion?“ | Niveau (`loa1`/`loa2`/`loa3`) | [04-orchestrierung.md](04-orchestrierung.md) |
| „Besitz, Wissen, Biometrie – wie viele davon?“ | `factorTypes`, Kombination mehrerer Faktoren | [04-orchestrierung.md](04-orchestrierung.md) |
| „Ist das wirklich Maras Gerät?“ | DPoP-Proof | [09-dpop.md](09-dpop.md) |
| „Darf dieses Handy eine Anmeldung auf einem anderen Gerät bestätigen?“ | `CONFIRM_PEER_LOGIN`, `PEER_APPROVAL` | [04-orchestrierung.md](04-orchestrierung.md) |
| „Ein Brief für einen Vorgang, ohne Konto“ | Einladung, `auth-invite-lookup`, `Subject.Invitation`, Claim `process` | [ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md) |

Jeder dieser Schritte funktioniert für Maras App-Kanal genauso wie für eine Anmeldung im Browser über
Keycloak. Zwischen den beiden Kanälen unterscheidet sich nur, *wer die Oberfläche zeigt* und *wie die
Anfrage abgesichert ist* ([05-api.md](05-api.md)). Abschnitt 7 zeigt beide zugleich: Dieselbe
Nutzerin nutzt zwei Kanäle, und die Bestätigung im einen Kanal führt zur Anmeldung im anderen.
