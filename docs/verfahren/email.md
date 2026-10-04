# Verfahren `email`

Die E-Mail-Adresse des Kontos: erst bestätigen, dass der Inhaber sie kontrolliert, dann wahlweise
als Anmeldeverfahren nutzen (Code an die bestätigte Adresse). Über dieselbe Adresse finden die
Lookup-Anmeldungen aller Verfahren das Konto.

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `confirm-email` | `ATTESTATION`, Claim `EMAIL` | 1 |
| `enroll-email` | `ENROLLMENT`, `withoutUserStep`, `requires = { ClaimRequirement(EMAIL, PROVEN) }` | 1 |
| `auth-email` | `KNOWN_ACCOUNT_AUTH` | 1 |
| `auth-email-lookup` | `ACCOUNT_LOOKUP_AUTH` | 1 |

Faktor `{knowledge}`, höchstens `loa1`; `confirm-email` erbringt keinen Faktor (`{}`). Ein aktiver
Eintrag je Konto (`allowsMultipleInstances = false`). Deklariert in
`tools/auth_email/EmailToolModule.kt`. Bestätigen und als Anmeldeverfahren einrichten sind zwei
Tools ([ADR-17](../adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md)).

## Die Adresse ist ein Attribut des Kontos

**Die bestätigte E-Mail-Adresse ist ein Attribut des Kontos, kein Credential eines Moduls.**
`confirm-email` liefert den `EMAIL`-Claim über `Completed.Attested`; die Journey übernimmt ihn per
`AccountService.recordClaims`. Sie wird zum EMAIL-Anker; was daraus im Datenmodell folgt, steht in
[06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1. Warum dafür die Rolle `ATTESTATION` gilt und nicht
`IDENTIFICATION`: [03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 4.

`enroll-email` baut darauf auf (`requires ClaimRequirement(EMAIL, PROVEN)`) und schreibt selbst
keinen Claim: Die Kontrolle über die Adresse ist schon bewiesen, ein zweiter Nachweis brächte
nichts. Sein Credential ist der Anker selbst (`EnrollmentRef` = `EMAIL_ANCHOR_ENROLLMENT`); `auth_email`
braucht deshalb keine eigene Credential-Tabelle und kein `EnrollmentCleanup`.

`auth-email-lookup`, `auth-sms-lookup` und `auth-password-lookup` finden das Konto über die
`resolveAccountByEmail`-Erweiterung von `AccountDirectory`; sie liefert nur eine Konto-ID, kein
Profil.

`confirm-email` prüft selbst **nicht**, ob die Adresse schon zu einem Konto gehört: Vor
der Eingabe des Codes ist sie nur eingetippt, nicht bewiesen. Wem die bestätigte Adresse gehört,
entscheidet danach die zentrale Auflösung. Gehört sie zu einem anderen Konto, geht das
verwerfbare Konto darin auf, sofern die bestätigte Identität dazu passt
([12-entscheidungen.md](../12-entscheidungen.md) ADR-20).

Auch `auth_email` nutzt nur `tool_api`: Konto-IDs und Ankerwerte liest es über `AccountDirectory`,
Attribute werden über Claims übernommen ([Tool-Architektur](../03-tool-architektur.md) Abschnitt 7).

Nur diese Adresse steht als `email`/`email_verified` in den Tokens; die Kontaktdaten des
Personenverzeichnisses nie ([05-api.md](../05-api.md) Abschnitt 3a, „ID-Token-Claims“). Der
Inhaber kann die Adresse zurücknehmen (`DELETE .../attributes/email`); dann fällt alles mit, was sie
per `requires` verlangt hat ([05-api.md](../05-api.md) Abschnitt 3a, „Verfahren verwalten“).

## Aufrufe und Abweichungen vom allgemeinen Muster

Die Tools folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms`
([05-api.md](../05-api.md) Abschnitt 2), mit diesen Abweichungen:

- `confirm-email` arbeitet mit zwei `PATCH`-Aufrufen (erst `email`, dann `code`) und legt den
  EMAIL-Anker an. Sonst arbeitet es wie `enroll-sms` ([Verfahren `sms`](sms.md)).
- `enroll-email` hat nur einen Schritt: Schon das Anlegen (`201`) schließt es ab. Es richtet die
  bestätigte Adresse als Anmeldeverfahren ein und setzt sie voraus
  (`requires = { ClaimRequirement(EMAIL, PROVEN) }`). Weil es ohne Schritt des Nutzers auskommt
  (`withoutUserStep`), startet der Orchestrator es nie von selbst, sondern zeigt die Auswahl
  ([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 2). Es meldet kein `amr`.
- `auth-email` braucht nur einen `PATCH` (`code`) und prüft gegen die bekannte, bestätigte
  E-Mail-Adresse des Kontos, nicht gegen eine in der Anfrage übergebene. Es arbeitet sonst wie
  `auth-sms`.
- `auth-email-lookup` (nur über `intent: "lookup_login"`) arbeitet mit zwei `PATCH`-Aufrufen: erst
  `{"email": "..."}` (findet das Konto und verschickt bei Erfolg den Code), dann `{"code": "..."}`
  ([05-api.md](../05-api.md) Abschnitt 3a, „Anmeldung über die E-Mail-Adresse“).

Wie viele Codes an eine Adresse gehen, begrenzt das Modul selbst (`EmailSendLimit`,
[03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 7, `RateLimit`).

## In der Demo

`demo.tan` steht bei `confirm-email` erst nach dem ersten `PATCH` in der Antwort; die Adresse füllt
die Auswahl der Testperson vor (`demo.persons`, [05-api.md](../05-api.md) Abschnitt 1).
