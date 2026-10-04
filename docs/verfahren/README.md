# Die Verfahren

Je Verfahren eine Seite. Ein Verfahren (`method`) gehört genau einem Modul und bündelt seine Tools:
das Einrichten, das Anmelden, die Anmeldung über die E-Mail-Adresse und weitere Rollen. Jede Seite
nennt die Tools mit Rolle und Fassungen, Faktoren und Niveau, beschreibt Ablauf und Datenmodell, die
Besonderheiten, die Aufrufe, soweit sie vom allgemeinen Muster abweichen, und die Fehlerfälle.

Was für alle Verfahren gilt, steht an anderer Stelle:

- wie ein Verfahren als Modul deklariert wird und was ein Tool meldet:
  [03-tool-architektur.md](../03-tool-architektur.md), dort in Abschnitt 8 auch der ganze Katalog;
- das gemeinsame Datenmodell (Konto, Anker, Claims, Änderungsprotokoll, eingerichtete Verfahren):
  [06-ablaeufe.md](../06-ablaeufe.md);
- das allgemeine Muster der Aufrufe (`POST`, `PATCH`, `GET` auf die Tool-Ressource) und ein
  durchgehendes Beispiel: [05-api.md](../05-api.md) Abschnitt 2;
- wie der Orchestrator die Ergebnisse bewertet: [04-orchestrierung.md](../04-orchestrierung.md).

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
