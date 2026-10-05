# Die Verfahren

Ein **Verfahren** ist ein Weg, auf dem ein Nutzer zeigt, wer er ist. Es gibt zwei Arten:

- **Anmeldeverfahren** richtet der Nutzer in seinem Konto ein und meldet sich später damit an,
  etwa mit einem Passwort, einem Code per SMS oder einem Schlüssel auf dem Smartphone.
- **Identifizierungsverfahren** stellen fest, wer jemand wirklich ist, etwa mit dem
  Online-Ausweis oder mit einem Freischaltcode, den die Versicherung per Brief schickt.

Die Tabelle unten nennt beide Arten. Zu jedem Verfahren gibt es eine eigene Seite.

### Wie ein Verfahren aufgebaut ist

Im Code heißt ein Verfahren `method`. Es gehört genau einem **Modul**, also einem abgegrenzten Teil
des Programmcodes. Ein Verfahren bündelt seine **Tools**. Ein Tool ist ein einzelner, abgeschlossener
Arbeitsschritt, den der Nutzer durchläuft, etwa „SMS einrichten“ oder „mit Passwort anmelden“. Zu
einem Verfahren gehören meist mehrere Tools mit verschiedenen Aufgaben (Rollen):

- das Einrichten,
- das Anmelden,
- die Anmeldung über die E-Mail-Adresse,
- und weitere Rollen.

Die Begriffe erklärt auch das [Glossar](../glossar/glossar.md).

### Was auf jeder Seite steht

Jede Seite beginnt mit einer kurzen Einleitung: was das Verfahren für den Nutzer ist und wozu es
dient. Danach folgen, soweit es für das Verfahren etwas zu sagen gibt:

- die Tools mit ihrer Rolle und ihren Fassungen,
- die Faktortypen und das höchste Niveau,
- der Ablauf und das Datenmodell,
- die Besonderheiten,
- die Aufrufe, soweit sie vom allgemeinen Muster abweichen,
- die Fehlerfälle.

Die Angaben im Abschnitt „Tools“ bedeuten:

- **Rolle**: was das Tool fachlich tut, etwa identifizieren (`IDENTIFICATION`), ein Verfahren
  einrichten (`ENROLLMENT`) oder ein bekanntes Konto anmelden (`KNOWN_ACCOUNT_AUTH`).
- **Fassung**: die Version der Schnittstelle eines Tools. Die meisten Tools haben nur Fassung 1.
- **Faktortyp**: die Art des Beweises. Es gibt Wissen (`knowledge`, etwa ein Passwort), Besitz
  (`possession`, etwa ein Smartphone) und Inhärenz (`inherence`, ein Körpermerkmal wie der
  Fingerabdruck).
- **Niveau**: wie sehr einer Anmeldung vertraut wird, von `loa1` (niedrig) bis `loa3` (hoch).
  Die Seiten nennen das höchste Niveau, das ein Verfahren liefern kann.

### Was an anderer Stelle steht

Was für alle Verfahren gilt, steht an anderer Stelle:

- Wie ein Verfahren als Modul deklariert wird und was ein Tool meldet:
  [03-tool-architektur.md](../03-tool-architektur.md). Dort steht in Abschnitt 8 auch der ganze
  Katalog.
- Das gemeinsame Datenmodell (Konto, Anker, Claims, Änderungsprotokoll, eingerichtete Verfahren):
  [06-ablaeufe.md](../06-ablaeufe.md).
- Das allgemeine Muster der Aufrufe (`POST`, `PATCH`, `GET` auf die Tool-Ressource) und ein
  durchgehendes Beispiel: [05-api.md](../05-api.md) Abschnitt 2.
- Wie der Orchestrator, also der Server dieses Projekts, die Ergebnisse bewertet:
  [04-orchestrierung.md](../04-orchestrierung.md).

### Alle Verfahren

| Verfahren | Tools | Wofür |
|---|---|---|
| [`fsc`](fsc.md) | `ident-fsc` | Identifizieren mit Personendaten und Freischaltcode |
| [`eid`](eid.md) | `ident-eid` | Identifizieren mit der Online-Ausweisfunktion |
| [`nect`](nect.md) | `ident-nect` | Identifizieren beim Dienst Nect (Ausweis, Reisepass, EUDI-Wallet) |
| [`kvnr`](kvnr.md) | `ident-kvnr` | eine bestätigte Identität der Person im Personenverzeichnis zuordnen |
| [`sms`](sms.md) | `enroll-sms`, `auth-sms`, `auth-sms-lookup` | Code per SMS an eine Telefonnummer |
| [`password`](password.md) | `enroll-password`, `auth-password`, `auth-password-lookup` | eigenes Passwort |
| [`email`](email.md) | `confirm-email`, `enroll-email`, `auth-email`, `auth-email-lookup` | Adresse bestätigen; Code an die bestätigte Adresse |
| [`device`](device.md) | `enroll-device`, `auth-device` | Schlüssel auf dem Gerät plus PIN oder Biometrie |
| [`kobil`](kobil.md) | `enroll-kobil`, `auth-kobil` | Gerätebindung über den Dienstleister KOBIL |
| [`qr`](qr.md) | `enroll-qr`, `auth-qr`, `auth-qr-lookup`, `approve-qr` | Web-Login, mit der App bestätigt |
| [`invite`](invite.md) | `auth-invite-lookup` | Vorgangszugang mit Einmalkennwort aus einer Einladung |
