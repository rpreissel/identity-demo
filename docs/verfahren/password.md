# Verfahren `password`

Ein eigenes Passwort, ohne eigenen Benutzernamen: Das Konto wird über seine bestätigte E-Mail-Adresse
gefunden. Gespeichert und geprüft wird das Passwort nur im Modul `auth_password`, auch wenn Keycloak
es auf seiner eigenen Anmeldeseite abfragt.

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `enroll-password` | `ENROLLMENT`, `changeable`, `requires = { ClaimRequirement(EMAIL, PROVEN) }`, schreibt den Claim `PASSWORD_EXISTS` | 1 |
| `auth-password` | `KNOWN_ACCOUNT_AUTH` | 1 |
| `auth-password-lookup` | `ACCOUNT_LOOKUP_AUTH` | 1 |

Faktor `{knowledge}`, höchstens `loa1`; ein aktiver Eintrag je Konto (`allowsMultipleInstances =
false`). Deklariert in `tools/auth_password/PasswordToolModule.kt`. Das Credential liegt in
`auth_password.enrollment`.

## Kein Benutzername

**`enroll-password`/`auth-password` haben kein eigenes Feld für einen Benutzernamen.** Diese
Aufgabe übernimmt der EMAIL-Anker ([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1), erzwungen über
`enroll("enroll-password", requires = setOf(ClaimRequirement(EMAIL, PROVEN)))`
([Tool-Architektur](../03-tool-architektur.md) Abschnitt 5). Wird die Adresse zurückgenommen, fällt
das Passwort mit ([05-api.md](../05-api.md) Abschnitt 3a, „Verfahren verwalten“, ADR-24).

`enroll-password` schreibt `PASSWORD_EXISTS`. Heute fragt das niemand ab, aber so ließe sich eine
Abhängigkeit eines anderen Verfahrens vom Passwort ausdrücken
([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 5).

## Aufrufe und Abweichungen vom allgemeinen Muster

Die Tools folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms`
([05-api.md](../05-api.md) Abschnitt 2), mit diesen Abweichungen:

- `enroll-password`/`auth-password` erwarten nur `{"password": "..."}` – **keinen** `username`.
  `enroll-password` schließt mit einem einzigen `PATCH` ab und setzt eine bereits bestätigte
  E-Mail-Adresse des Kontos voraus; fehlt sie, lehnt schon das Anlegen mit `409` ab.
- `auth-password` arbeitet wie `auth-sms`: Der Orchestrator übergibt die aktive `EnrollmentRef`
  des Kontos an den Handler ([Verfahren `sms`](sms.md)).
- `auth-password-lookup` (nur über `intent: "lookup_login"`) erwartet `{"email": "...", "password": "..."}`
  in einem einzigen `PATCH` ([05-api.md](../05-api.md) Abschnitt 3a, „Anmeldung über die
  E-Mail-Adresse“).
- `enroll-password` ist `changeable`: Über `POST .../methods/{methodInstanceId}/changes` läuft es
  noch einmal, und das Tool nennt in `stepData`, dass es ersetzt
  (`{"kind":"enroll-password","missingFields":["password"],"replaces":true}`,
  [05-api.md](../05-api.md) Abschnitt 3a, „Verfahren verwalten“).

## Passwort speichern und prüfen (`auth_password`)

- **Nur als Hash:** Argon2id mit den OWASP-Werten (19 MiB Speicher, 2 Durchläufe, 1 Spur;
  `PasswordHasher`). Ein anderes Hash-Format gibt es nicht.
- **Umhashen bei der Anmeldung:** Werden die Werte später angehoben, erkennt `needsRehash` einen
  schwächeren Hash. Nach einer **erfolgreichen** Prüfung schreibt `PasswordHasher.upgrade` den neuen
  Hash in derselben Transaktion. Niemand muss dafür sein Passwort neu setzen.
- **Gleicher Aufwand ohne Passwort:** Hat das Konto kein Passwort, rechnet `matches` trotzdem einmal
  Argon2id gegen einen Platzhalter-Hash. Sonst verriete die Antwortzeit, ob es zu einer Adresse ein
  Passwort gibt.
- **Regeln für ein neues Passwort** (`PasswordPolicy`, für `enroll-password`; über Keycloak lässt
  sich kein Passwort setzen): mindestens 8, höchstens 128 Zeichen und keines aus der Liste der
  gängigsten Passwörter (`auth_password/common-passwords.txt`, ohne Beachtung von
  Groß-/Kleinschreibung). Verstöße sind `400` mit einem Text, der die Regel nennt.

Über den Port `PasswordCredentialPort` prüft oder ersetzt das Modul das Passwort auch für Aufrufer
ohne Kanal und Tool-Sitzung: das Entsperren per Passwort in `auth_kobil`
([Verfahren `kobil`](kobil.md)) und Keycloaks Passwortformular (unten).

## Von Server zu Server: Keycloaks eigenes Passwort-Credential (`MgmtPasswordController`)

Ohne Zustand, ohne Kanal und ohne ToolSession: Keycloaks eigene UserStorage-SPI
(`OrchestratorStorageProvider`) prüft und setzt Passwörter für das Konto, das Keycloak über das
Nutzerattribut `orchestratorAccountId` kennt. Keycloak weist sich dabei mit derselben
`kc-peer-auth`-Signatur aus wie bei den anderen Aufrufen von Server zu Server
([DPoP-Bindung](../09-dpop.md)/[12-entscheidungen.md](../12-entscheidungen.md) ADR-7,
[05-api.md](../05-api.md) Abschnitt 3b). Allerdings wird
`channel_binding` hier für einen anderen Zweck genutzt: Der Claim trägt die `accountId` und wird
gegen den Pfadparameter geprüft.

Die Endpunkte gehören dem Modul `auth_password`, liegen aber wie alles, was nur Keycloak aufruft,
unter `/kc/` und damit außerhalb des eingefrorenen Vertrags (ADR-50). Sie nehmen nur Keycloaks
Assertion an (`@BindingKey(keycloakOnly = true)`); ein DPoP-Beweis bekommt `401`. Das Ergebnis bucht
der Orchestrator über den Port `KeycloakToolCalls`: eine Prüfung auf die Kontosperre wie in einer
Journey. Ein Passwort ändert Keycloak nie: Das geht nur über die Verwaltung der Verfahren, hinter
deren Niveauprüfung. Die Erweiterung lehnt Keycloaks „Passwort ändern“ und „Passwort zurücksetzen“
ab, statt das Passwort bei sich zu speichern, und das Realm schaltet Keycloaks eigene Required
Actions ab (`V7__locked_down_defaults`).

- `POST /orchestrator/api/v1/kc/accounts/{accountId}/password-checks` – prüft `{"password": "..."}`
  gegen das gespeicherte Credential; Antwort `{"valid": true|false}`. Jeder Fehlversuch zählt auf
  dieselbe Kontosperre wie `auth-password` im App-Kanal ([Betrieb](../07-betrieb.md) Abschnitt 4);
  gesperrt ist die Antwort `false`, auch für das richtige Passwort, bei gleichem Zeitaufwand.
  Zusätzlich hat das Realm Keycloaks eigenen Schutz gegen Passwort-Raten eingeschaltet.

Die übrigen Endpunkte unter `/kc/` stehen in [05-api.md](../05-api.md) Abschnitt 3b.

## Fehlerfälle

- Passwort verstößt gegen die Regeln (`enroll-password`) -> `400`.
- Keine bestätigte E-Mail-Adresse beim Anlegen von `enroll-password` -> `409`.
- Falsches Passwort -> gewöhnlicher Fehlversuch (`200` mit `stepData.error`), der auf die
  Kontosperre zählt.

## In der Demo

`demo.password` (ein fester Demo-Wert) steht in jeder `InProgress`-Antwort aller drei
Passwort-Tools ([05-api.md](../05-api.md) Abschnitt 1, „Das `demo`-Objekt“); bei
`auth-password-lookup` ist er unabhängig vom gefundenen Konto.
