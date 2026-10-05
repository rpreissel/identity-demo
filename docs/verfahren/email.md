# Verfahren `email`

**Was es ist:** Der Nutzer gibt seine E-Mail-Adresse an. Der Server schickt einen Code an diese
Adresse, und der Nutzer tippt ihn ein. Damit ist bestätigt, dass er das Postfach kontrolliert. Wenn
er möchte, kann er sich danach auch per Code an diese Adresse anmelden.

**Wozu es dient:** Die bestätigte Adresse hat zwei Aufgaben. Erstens kann sie als einfaches
Anmeldeverfahren dienen (Niveau `loa1`). Zweitens finden die Anmeldungen über die E-Mail-Adresse aller
Verfahren (etwa mit SMS oder Passwort) das Konto über diese Adresse.

Es gibt also zwei Schritte: erst die Adresse bestätigen, dann wahlweise als Anmeldeverfahren nutzen
(Code an die bestätigte Adresse).

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat vier Tools:

- `confirm-email` bestätigt die Adresse.
- `enroll-email` richtet die bestätigte Adresse als Anmeldeverfahren ein.
- `auth-email` meldet ein bekanntes Konto mit einem Code an die Adresse an.
- `auth-email-lookup` sucht das Konto über die eingegebene Adresse und meldet es an.

| toolId | Rolle | Fassungen |
|---|---|---|
| `confirm-email` | `ATTESTATION`, Claim `EMAIL` | 1 |
| `enroll-email` | `ENROLLMENT`, `withoutUserStep`, `requires = { ClaimRequirement(EMAIL, PROVEN) }` | 1 |
| `auth-email` | `KNOWN_ACCOUNT_AUTH` | 1 |
| `auth-email-lookup` | `ACCOUNT_LOOKUP_AUTH` | 1 |

Zur Tabelle: Die Rolle `ATTESTATION` heißt, das Tool bestätigt eine Angabe. Ein **Claim** ist eine
Angabe über den Kontoinhaber, die im Konto gespeichert wird, hier die E-Mail-Adresse. `requires`
nennt, was vorher erfüllt sein muss. `withoutUserStep` heißt, das Tool braucht keine Eingabe des
Nutzers.

Das Verfahren erbringt den Faktortyp Wissen (`{knowledge}`) und liefert höchstens das Niveau
`loa1`. `confirm-email` erbringt keinen Faktor (`{}`). Je Konto gibt es höchstens einen aktiven
Eintrag (`allowsMultipleInstances = false`). Deklariert ist das Verfahren in
`tools/auth_email/EmailToolModule.kt`. Bestätigen und als Anmeldeverfahren einrichten sind zwei
getrennte Tools ([ADR-17](../adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md)).

## Die Adresse ist ein Attribut des Kontos

**Die bestätigte E-Mail-Adresse ist ein Attribut des Kontos, kein Credential eines Moduls.** Ein
Credential wäre das gespeicherte Merkmal eines einzelnen Anmeldeverfahrens. Die Adresse dagegen
gehört dem Konto selbst.

`confirm-email` liefert den `EMAIL`-Claim über `Completed.Attested`. Die **Journey**, also der
geführte Ablauf, in dem das Tool läuft, übernimmt ihn per `AccountService.recordClaims`. Die
Adresse wird zum EMAIL-Anker. Ein **Anker** ist eine Angabe, über die sich ein Konto eindeutig
wiederfinden lässt. Was daraus im Datenmodell folgt, steht in [06-ablaeufe.md](../06-ablaeufe.md)
Abschnitt 1. Warum dafür die Rolle `ATTESTATION` gilt und nicht `IDENTIFICATION`, steht in
[03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 4.

`enroll-email` baut darauf auf (`requires ClaimRequirement(EMAIL, PROVEN)`) und schreibt selbst
keinen Claim. Die Kontrolle über die Adresse ist schon bewiesen, ein zweiter Nachweis brächte
nichts. Sein Credential ist der Anker selbst (`EnrollmentRef` = `EMAIL_ANCHOR_ENROLLMENT`). Deshalb
braucht `auth_email` keine eigene Credential-Tabelle und kein `EnrollmentCleanup`.

`auth-email-lookup`, `auth-sms-lookup` und `auth-password-lookup` finden das Konto über die
`resolveAccountByEmail`-Erweiterung von `AccountDirectory`. Sie liefert nur eine Konto-ID, kein
Profil.

`confirm-email` prüft selbst **nicht**, ob die Adresse schon zu einem Konto gehört. Denn bevor der
Nutzer den Code eingibt, ist die Adresse nur eingetippt, nicht bewiesen. Wem die bestätigte Adresse
gehört, entscheidet danach die zentrale Auflösung. Gehört sie zu einem anderen Konto, arbeitet die
Journey mit diesem anderen Konto weiter und übernimmt die Daten des verwerfbaren Kontos. Das gilt
nur, wenn die bestätigte Identität dazu passt. Verwerfbar heißt: Das Konto ist keiner Person
zugeordnet, und in ihm wurde nie ein Anmeldeverfahren eingerichtet. Mehr dazu in
[12-entscheidungen.md](../12-entscheidungen.md) ADR-20.

Auch `auth_email` nutzt nur `tool_api`, die Schnittstelle für Tool-Module. Konto-IDs und
Ankerwerte liest es über `AccountDirectory`. Attribute werden über Claims übernommen
([Tool-Architektur](../03-tool-architektur.md) Abschnitt 7).

Nur diese Adresse steht als `email`/`email_verified` in den Tokens. Die Kontaktdaten des
Personenverzeichnisses stehen dort nie ([05-api.md](../05-api.md) Abschnitt 3a,
„ID-Token-Claims“). Der Inhaber kann die Adresse zurücknehmen (`DELETE .../attributes/email`). Dann
wird auch alles abgeschaltet, was die Adresse per `requires` vorausgesetzt hat
([05-api.md](../05-api.md) Abschnitt 3a, „Verfahren verwalten“).

## Aufrufe und Abweichungen vom allgemeinen Muster

Die Tools folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms`
([05-api.md](../05-api.md) Abschnitt 2). Es gibt diese Abweichungen:

- `confirm-email` arbeitet mit zwei `PATCH`-Aufrufen (erst `email`, dann `code`) und legt den
  EMAIL-Anker an. Sonst arbeitet es wie `enroll-sms` ([Verfahren `sms`](sms.md)).
- `enroll-email` hat nur einen Schritt: Schon das Anlegen (`201`) schließt es ab. Es richtet die
  bestätigte Adresse als Anmeldeverfahren ein und setzt sie voraus
  (`requires = { ClaimRequirement(EMAIL, PROVEN) }`). Weil es ohne Schritt des Nutzers auskommt
  (`withoutUserStep`), startet der Orchestrator es nie von selbst. Stattdessen zeigt er die Auswahl
  ([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 2). Es meldet kein `amr`, also
  keinen Eintrag in der Liste der Verfahren, mit denen sich der Nutzer angemeldet hat.
- `auth-email` braucht nur einen `PATCH` (`code`). Es prüft gegen die bekannte, bestätigte
  E-Mail-Adresse des Kontos, nicht gegen eine Adresse aus der Anfrage. Sonst arbeitet es wie
  `auth-sms`.
- `auth-email-lookup` ist nur über `intent: "lookup_login"` erreichbar. Es arbeitet mit zwei
  `PATCH`-Aufrufen: erst `{"email": "..."}` (findet das Konto und verschickt bei Erfolg den Code),
  dann `{"code": "..."}` ([05-api.md](../05-api.md) Abschnitt 3a, „Anmeldung über die
  E-Mail-Adresse“).

Das Modul begrenzt selbst, wie viele Codes an eine Adresse gehen (`EmailSendLimit`,
[03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 7, `RateLimit`).

## In der Demo

Bei `confirm-email` steht `demo.tan` erst nach dem ersten `PATCH` in der Antwort. Die Adresse füllt
die Auswahl der Testperson vor (`demo.persons`, [05-api.md](../05-api.md) Abschnitt 1).
