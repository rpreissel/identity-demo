# Verfahren `invite`

Ein Vorgangszugang mit Einmalkennwort: Eine Person ohne Konto erledigt genau einen Vorgang, zu dem
das Personenverzeichnis sie per Brief eingeladen hat
([ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Die Anmeldung endet mit einer
Einladung als Subjekt statt eines Kontos.

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `auth-invite-lookup` | `ACCOUNT_LOOKUP_AUTH`, ohne Gegenstück mit `KNOWN_ACCOUNT_AUTH` | 1 |

Faktor `{possession}`, höchstens `loa2`; das Niveau eines Durchlaufs ist das der Einladung (`loa1`
oder `loa2`) und wird je Durchlauf gemeldet. Deklariert in `tools/auth_invite/InviteToolModule.kt`.
Angeboten wird das Tool zunächst nur im Web-Kanal; in der Voreinstellung des App-Kanals ist es
gesperrt.

## Was `auth-invite-lookup` vom Personenverzeichnis bekommt

Die Einladungen gehören dem Personenverzeichnis; das Tool fragt es über den Port `Invitations`
(`redeem(personId, code)`), wie `ident-fsc` den Freischaltcode über `ActivationCodes`. Der Controller
löst KVNR oder Partnernummer über `PersonDirectory` zur Person auf und fragt die
Personen-Mengenbegrenzung; das Tool prüft das Kennwort gegen die offenen Einladungen dieser Person.
Das Ergebnis ist `Completed.Authenticated` mit `Subject.Invitation` und dem Niveau der Einladung.
Die Journey bindet dann kein Konto, sondern die Einladung als Subjekt des Kanals
(`ChannelSession.invitation`). Ein Fehlversuch nennt `Attempted.Person`
([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 3).

## Ablauf

1. **Einladen.** Das Personenverzeichnis stellt für eine Person eine Einladung aus: Vorgang, Niveau
   (`loa1` oder `loa2`), Frist. Es erzeugt ein Einmalkennwort mit zwölf Zeichen in Vierergruppen,
   speichert nur die Id (SHA-256 über `personId:KENNWORT:vorgang`) und schickt den Klartext per
   Brief.
2. **Anmelden.** Die Website startet eine gewöhnliche Anmeldung über den Browser-Client. Ohne Konto
   bietet die Auswahl `auth-invite-lookup` an, neben den Lookup-Anmeldungen. Die Person gibt KVNR
   oder Partnernummer und das
   Kennwort ein. Der Controller löst die Nummer zur Person auf; das Tool fragt das Verzeichnis, ob das
   Kennwort eine offene Einladung genau dieser Person öffnet (`Invitations.redeem`). Ein Fehlversuch
   zählt gegen die Person (Personen-Mengenbegrenzung wie beim Freischaltcode) und sieht für jede
   Ursache gleich aus.
3. **Binden.** Bei Erfolg wird die Einladung Subjekt des Kanals, kein Konto wird gesucht oder
   angelegt. Liegt das Niveau der Einladung unter dem verlangten, bricht die Journey vorher ab,
   bevor etwas gebunden wird.
   Keycloak setzt den Nutzer aus der Einladungs-Federation (`f:<UUID>:<Id>`); seine Tokens tragen
   die Stammdaten der Person und die Claims `process` und `invitation`, aber kein `orchestrator_account_id`.
4. **Wiederkommen.** Bis zur Frist oder zum Abschluss kann sich die Person beliebig oft wieder
   anmelden, wie beim Freischaltcode.
5. **Beenden.** Das Fachsystem meldet den Vorgang beim Personenverzeichnis ab, mit der Id der
   Einladung, die es selbst bilden kann. Das Verzeichnis meldet `InvitationEnded`; der Orchestrator
   meldet den Einladungs-Nutzer in Keycloak ab, und jeder weitere Refresh scheitert, weil Keycloak den
   Nutzer nun deaktiviert liest. Ein Widerruf läuft genauso, eine abgelaufene Frist wirkt von selbst.

## Im Web-Kanal

Keycloak liest den Einladungs-Nutzer über `GET /orchestrator/api/v1/kc/invitations/{invitation}`,
gesichert wie die Kontosuche (Peer-Auth-Assertion, `channel_binding` = Id der Einladung); die
Antwort trägt `enabled = false`, sobald die Einladung abgeschlossen, widerrufen oder abgelaufen ist.
`authData.subject` ist dann `{"type": "invitation", "id": "<Hash>"}`, und ein Kanal, der der
Einladung nicht schon gehört, lässt sich mit diesem Subjekt nicht übernehmen
([05-api.md](../05-api.md) Abschnitt 3b). Die Abmeldung meldet Keycloak über
`POST /orchestrator/api/v1/kc/invitations/{invitation}/sign-outs` (ebenda).

## Grenzen eines Einladungs-Kanals

Ein Einladungs-Kanal kann keine Kontofunktion aufrufen (Verfahren verwalten, Konto löschen,
QR-Bestätigung), gibt keine Evidenz an einen späteren Flow-Durchlauf weiter und kann nicht per Step-up
über das Niveau der Einladung steigen. Bei `restore-data` gibt er nichts zurück: Seine Evidenz gehört
der Einladung und darf in keinen späteren Durchlauf für ein Konto wandern. Konto und Einladung
teilen sich nie eine Keycloak-Sitzung; gewechselt wird über die Abmeldung.

## In der Demo

Das Ausstellen geschieht auf der Seite „Einladungen“ von `/personenverzeichnis/`; der Brief
liegt im Briefkasten. Auf der Anmeldeseite bietet die Demo eine Auswahl der offenen Einladungen an,
die Nummer und Kennwort einträgt (`invitations` im Demo-Block, ADR-28).
