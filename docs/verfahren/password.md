# Verfahren `password`

**Was es ist:** Der Nutzer legt ein eigenes Passwort fest. Zum Anmelden gibt er seine bestätigte
E-Mail-Adresse und dieses Passwort ein. Einen eigenen Benutzernamen gibt es nicht.

**Wozu es dient:** Es ist ein einfaches Anmeldeverfahren. Es beweist Wissen (das Passwort) und
reicht allein für das niedrigste Niveau `loa1`.

Das Konto wird über seine bestätigte E-Mail-Adresse gefunden. Gespeichert und geprüft wird das
Passwort nur im Modul `auth_password`. Das gilt auch dann, wenn Keycloak, das auf der Website die
Anmeldung führt, das Passwort auf seiner eigenen Anmeldeseite abfragt.

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat drei Tools: eines zum Einrichten (`enroll-password`), eines zum Anmelden eines
bekannten Kontos (`auth-password`) und eines zum Anmelden über die E-Mail-Adresse
(`auth-password-lookup`).

| toolId | Rolle | Fassungen |
|---|---|---|
| `enroll-password` | `ENROLLMENT`, `changeable`, `requires = { ClaimRequirement(EMAIL, PROVEN) }`, schreibt den Claim `PASSWORD_EXISTS` | 1 |
| `auth-password` | `KNOWN_ACCOUNT_AUTH` | 1 |
| `auth-password-lookup` | `ACCOUNT_LOOKUP_AUTH` | 1 |

Zur Tabelle: `changeable` heißt, das Passwort lässt sich später ändern. `requires` nennt, was vorher
erfüllt sein muss, hier eine bestätigte E-Mail-Adresse. Ein **Claim** ist eine Angabe über den
Kontoinhaber, die im Konto gespeichert wird.

Das Verfahren erbringt den Faktortyp Wissen (`{knowledge}`) und liefert höchstens das Niveau
`loa1`. Je Konto gibt es höchstens einen aktiven Eintrag
(`allowsMultipleInstances = false`). Deklariert ist das Verfahren in
`tools/auth_password/PasswordToolModule.kt`. Das Credential, also das gespeicherte Merkmal für die Anmeldung, liegt in `auth_password.enrollment`.

## Kein Benutzername

**`enroll-password`/`auth-password` haben kein eigenes Feld für einen Benutzernamen.** Diese
Aufgabe übernimmt der EMAIL-Anker. Ein **Anker** ist eine Angabe, über die sich ein Konto eindeutig
wiederfinden lässt, hier die bestätigte E-Mail-Adresse ([06-ablaeufe.md](../06-ablaeufe.md)
Abschnitt 1). Die Voraussetzung wird erzwungen über
`enroll("enroll-password", requires = setOf(ClaimRequirement(EMAIL, PROVEN)))`
([Tool-Architektur](../03-tool-architektur.md) Abschnitt 5). Nimmt der Nutzer die Adresse zurück,
wird auch das Passwort-Verfahren abgeschaltet ([05-api.md](../05-api.md) Abschnitt 3a,
„Verfahren verwalten“, ADR-24).

`enroll-password` schreibt den Claim `PASSWORD_EXISTS`. Heute fragt ihn niemand ab. Aber so ließe
sich ausdrücken, dass ein anderes Verfahren ein Passwort voraussetzt
([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 5).

## Aufrufe und Abweichungen vom allgemeinen Muster

Die Tools folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms`
([05-api.md](../05-api.md) Abschnitt 2). Es gibt diese Abweichungen:

- `enroll-password`/`auth-password` erwarten nur `{"password": "..."}`, also **keinen** `username`.
- `enroll-password` schließt mit einem einzigen `PATCH` ab. Es setzt eine bereits bestätigte
  E-Mail-Adresse des Kontos voraus. Fehlt sie, lehnt der Server schon das Anlegen mit `409` ab.
- `auth-password` arbeitet wie `auth-sms`: Der Orchestrator übergibt die aktive `EnrollmentRef`
  des Kontos an den Handler ([Verfahren `sms`](sms.md)).
- `auth-password-lookup` ist nur über `intent: "lookup_login"` erreichbar. Es erwartet
  `{"email": "...", "password": "..."}` in einem einzigen `PATCH` ([05-api.md](../05-api.md)
  Abschnitt 3a, „Anmeldung über die E-Mail-Adresse“).
- `enroll-password` ist `changeable`. Über `POST .../methods/{methodInstanceId}/changes` läuft es
  noch einmal, und das Tool zeigt in `stepData` an, dass es das bisherige Passwort ersetzt
  (`{"kind":"enroll-password","missingFields":["password"],"replaces":true}`,
  [05-api.md](../05-api.md) Abschnitt 3a, „Verfahren verwalten“).

## Passwort speichern und prüfen (`auth_password`)

Das Modul `auth_password` schützt die Passwörter auf mehreren Wegen:

- **Nur als Hash:** Das Passwort wird nur als Hash gespeichert, mit Argon2id und den
  OWASP-Werten (19 MiB Speicher, 2 Durchläufe, 1 Spur; `PasswordHasher`). Ein anderes Hash-Format
  gibt es nicht.
- **Neu hashen bei der Anmeldung:** Werden die Werte später angehoben, erkennt `needsRehash` einen
  schwächeren Hash. Nach einer **erfolgreichen** Prüfung schreibt `PasswordHasher.upgrade` den neuen
  Hash in derselben Transaktion. Niemand muss dafür sein Passwort neu setzen.
- **Gleicher Aufwand ohne Passwort:** Hat das Konto kein Passwort, rechnet `matches` trotzdem einmal
  Argon2id gegen einen Platzhalter-Hash. Sonst würde die Antwortzeit verraten, ob es zu einer
  Adresse ein Passwort gibt.
- **Regeln für ein neues Passwort** (`PasswordPolicy`, für `enroll-password`): Das Passwort hat
  mindestens 8 und höchstens 128 Zeichen. Es steht nicht in der Liste der gängigsten Passwörter
  (`auth_password/common-passwords.txt`); Groß- und Kleinschreibung spielen dabei keine Rolle. Ein
  Verstoß führt zu `400` mit einem Text, der die Regel nennt. Über Keycloak lässt sich kein
  Passwort setzen.

Über den Port `PasswordCredentialPort` prüft oder ersetzt das Modul das Passwort auch für Aufrufer,
die keinen Kanal und keine Tool-Sitzung haben. Ein **Port** ist eine fest vereinbarte
Schnittstelle. Diese Aufrufer sind:

- das Entsperren per Passwort in `auth_kobil` ([Verfahren `kobil`](kobil.md)),
- Keycloaks Passwortformular (unten).

## Von Server zu Server: Keycloaks eigenes Passwort-Credential (`MgmtPasswordController`)

Keycloak fragt auf seiner Anmeldeseite selbst nach dem Passwort. Prüfen lässt es das Passwort aber
beim Orchestrator. Dieser Aufruf läuft ohne Zustand, ohne Kanal und ohne ToolSession. Keycloaks
eigene Schnittstelle für Nutzerspeicher (UserStorage-SPI, hier `OrchestratorStorageProvider`) prüft
und setzt Passwörter für das Konto, das Keycloak über das Nutzerattribut `orchestratorAccountId`
kennt.

Keycloak weist sich dabei mit derselben `kc-peer-auth`-Signatur aus wie bei den anderen Aufrufen
von Server zu Server. Mehr dazu in [DPoP-Bindung](../09-dpop.md),
[12-entscheidungen.md](../12-entscheidungen.md) ADR-7 und [05-api.md](../05-api.md) Abschnitt 3b.
Allerdings dient `channel_binding` hier einem anderen Zweck: Der Claim enthält die `accountId`,
und der Server prüft ihn gegen den Pfadparameter.

Die Endpunkte gehören dem Modul `auth_password`. Sie liegen aber wie alles, was nur Keycloak
aufruft, unter `/kc/` und damit außerhalb des eingefrorenen Vertrags (ADR-50). Sie nehmen nur
Keycloaks Assertion an, also den signierten Nachweis, dass die Anfrage von Keycloak kommt
(`@BindingKey(keycloakOnly = true)`). Eine Anfrage mit DPoP-Beweis bekommt `401`.

Das Ergebnis bucht der Orchestrator über den Port `KeycloakToolCalls`, und zwar als Prüfung auf die
Kontosperre wie in einer Journey. Keycloak ändert ein Passwort nie. Das geht nur über die Verwaltung
der Verfahren, die vorher das Niveau prüft. Die Erweiterung lehnt Keycloaks „Passwort ändern“ und
„Passwort zurücksetzen“ ab, statt das Passwort bei sich zu speichern. Das Realm schaltet außerdem
Keycloaks eigene Required Actions ab (`V7__locked_down_defaults`).

- `POST /orchestrator/api/v1/kc/accounts/{accountId}/password-checks` – prüft `{"password": "..."}`
  gegen das gespeicherte Credential. Die Antwort ist `{"valid": true|false}`. Jeder Fehlversuch
  zählt auf dieselbe Kontosperre wie `auth-password` im App-Kanal ([Betrieb](../07-betrieb.md)
  Abschnitt 4). Ist das Konto gesperrt, ist die Antwort `false`, auch für das richtige Passwort und
  bei gleichem Zeitaufwand. Zusätzlich hat das Realm Keycloaks eigenen Schutz gegen das Erraten von
  Passwörtern eingeschaltet.

Die übrigen Endpunkte unter `/kc/` stehen in [05-api.md](../05-api.md) Abschnitt 3b.

## Fehlerfälle

- Das Passwort verstößt gegen die Regeln (`enroll-password`) -> `400`.
- Es gibt keine bestätigte E-Mail-Adresse beim Anlegen von `enroll-password` -> `409`.
- Falsches Passwort -> gewöhnlicher Fehlversuch (`200` mit `stepData.error`), der auf die
  Kontosperre zählt.

## In der Demo

`demo.password` ist ein fester Demo-Wert. Er steht in jeder `InProgress`-Antwort aller drei
Passwort-Tools ([05-api.md](../05-api.md) Abschnitt 1, „Das `demo`-Objekt“). Bei
`auth-password-lookup` ist er unabhängig vom gefundenen Konto.
