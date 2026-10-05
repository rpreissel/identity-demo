# Verfahren `invite`

**Was es ist:** Eine Person ohne Konto bekommt vom Personenverzeichnis, den Stammdaten der
Versicherung, einen Brief mit einer Einladung zu einem bestimmten Vorgang. Im Brief steht ein
Einmalkennwort. Auf der Website meldet sie sich mit ihrer KVNR oder Partnernummer und diesem
Kennwort an.

**Wozu es dient:** Die Person kann genau diesen einen Vorgang erledigen, ohne ein Konto anzulegen.
Das nennt das Projekt **Vorgangszugang**. Mehr dazu in
[ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

Die Anmeldung endet mit einer Einladung als **Subjekt** statt mit einem Konto. Das Subjekt ist das,
wem ein angemeldeter Kanal gehört: sonst ein Konto, hier die Einladung.

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat ein einziges Tool, `auth-invite-lookup`. Seine Rolle ist `ACCOUNT_LOOKUP_AUTH`:
Es findet anhand der Eingabe, wer sich anmeldet. Ein Gegenstück für ein schon bekanntes Konto
(`KNOWN_ACCOUNT_AUTH`) gibt es nicht.

| toolId | Rolle | Fassungen |
|---|---|---|
| `auth-invite-lookup` | `ACCOUNT_LOOKUP_AUTH`, ohne Gegenstück mit `KNOWN_ACCOUNT_AUTH` | 1 |

Das Verfahren erbringt den Faktortyp Besitz (`{possession}`) und liefert höchstens das Niveau
`loa2`. Das Niveau eines Durchlaufs ist das der Einladung (`loa1` oder `loa2`). Das Tool meldet es je
Durchlauf. Deklariert ist das Verfahren in `tools/auth_invite/InviteToolModule.kt`.

Das Tool wird zunächst nur im Web-Kanal angeboten, also bei der Anmeldung auf der Website. In der
Voreinstellung des App-Kanals ist es gesperrt.

## Was `auth-invite-lookup` vom Personenverzeichnis bekommt

Die Einladungen gehören dem Personenverzeichnis. Das Tool fragt es über den Port `Invitations`
(`redeem(personId, code)`). Ein **Port** ist eine fest vereinbarte Schnittstelle zu einem anderen
System. Genauso fragt `ident-fsc` den Freischaltcode über `ActivationCodes` ab.

Die Aufgaben sind so verteilt:

- Der Controller löst KVNR oder Partnernummer über `PersonDirectory` zur Person auf. Außerdem fragt
  er die Mengenbegrenzung für die Person ab.
- Das Tool prüft das Kennwort gegen die offenen Einladungen dieser Person.

Das Ergebnis ist `Completed.Authenticated` mit `Subject.Invitation` und dem Niveau der Einladung.
Die **Journey**, also der geführte Ablauf der Anmeldung, bindet dann kein Konto an den Kanal,
sondern die Einladung als Subjekt (`ChannelSession.invitation`). Ein Fehlversuch nennt
`Attempted.Person` ([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 3).

## Ablauf

1. **Einladen.** Das Personenverzeichnis stellt für eine Person eine Einladung aus, mit Vorgang,
   Niveau (`loa1` oder `loa2`) und Frist. Es erzeugt ein Einmalkennwort mit zwölf Zeichen in
   Vierergruppen. Es speichert nur die Id (SHA-256 über `personId:KENNWORT:vorgang`) und schickt den
   Klartext per Brief.
2. **Anmelden.** Die Website startet eine gewöhnliche Anmeldung über den Browser-Client. Hat die
   Person kein Konto, bietet die Auswahl `auth-invite-lookup` an, neben den Anmeldungen über die
   E-Mail-Adresse. Die Person gibt KVNR oder Partnernummer und das Kennwort ein. Der Controller
   löst die Nummer zur Person auf. Das Tool fragt das Verzeichnis, ob das Kennwort eine offene
   Einladung genau dieser Person öffnet (`Invitations.redeem`). Ein Fehlversuch zählt gegen die
   Person (Mengenbegrenzung für die Person wie beim Freischaltcode). Er sieht für jede Ursache
   gleich aus.
3. **Binden.** Bei Erfolg wird die Einladung Subjekt des Kanals. Es wird kein Konto gesucht oder
   angelegt. Liegt das Niveau der Einladung unter dem verlangten, bricht die Journey vorher ab,
   bevor etwas gebunden wird.
   Keycloak setzt den Nutzer aus der Einladungs-Federation (`f:<UUID>:<Id>`). Seine Tokens
   enthalten die Stammdaten der Person und die Claims `process` und `invitation`, aber kein
   `orchestrator_account_id`.
4. **Wiederkommen.** Bis zur Frist oder bis zum Abschluss des Vorgangs kann sich die Person beliebig
   oft wieder anmelden, wie beim Freischaltcode.
5. **Beenden.** Das Fachsystem meldet den Vorgang beim Personenverzeichnis ab. Dazu nutzt es die Id
   der Einladung, die es selbst bilden kann. Das Verzeichnis meldet `InvitationEnded`. Der
   Orchestrator meldet daraufhin den Einladungs-Nutzer in Keycloak ab. Jeder weitere Refresh
   scheitert, weil Keycloak den Nutzer nun als deaktiviert liest. Ein Widerruf läuft genauso ab. Eine
   abgelaufene Frist wirkt von selbst.

## Im Web-Kanal

Keycloak, das auf der Website die Anmeldung führt, liest den Einladungs-Nutzer über
`GET /orchestrator/api/v1/kc/invitations/{invitation}`. Dieser Aufruf ist gesichert wie die
Kontosuche: mit der Peer-Auth-Assertion, also dem signierten Nachweis, dass die Anfrage von Keycloak
kommt, und mit `channel_binding` = Id der Einladung. Die Antwort enthält `enabled = false`, sobald
die Einladung abgeschlossen, widerrufen oder abgelaufen ist.

`authData.subject` ist dann `{"type": "invitation", "id": "<Hash>"}`. Ein Kanal, der nicht schon der
Einladung gehört, lässt sich mit diesem Subjekt nicht übernehmen ([05-api.md](../05-api.md)
Abschnitt 3b). Die Abmeldung meldet Keycloak über
`POST /orchestrator/api/v1/kc/invitations/{invitation}/sign-outs` (ebenda).

## Grenzen eines Einladungs-Kanals

Ein Kanal, der einer Einladung gehört, kann weniger als ein Kanal mit Konto:

- Er kann keine Kontofunktion aufrufen, also weder Verfahren verwalten noch das Konto löschen noch
  einen QR-Login bestätigen.
- Er gibt keine Evidenz, also keine Nachweise aus seiner Sitzung, an einen späteren
  Flow-Durchlauf weiter.
- Er kann nicht per Step-up über das Niveau der Einladung steigen. Ein Step-up heißt, dass ein
  angemeldeter Nutzer noch etwas beweist, um ein höheres Niveau zu erreichen.
- Bei `restore-data` gibt er nichts zurück. Seine Evidenz gehört der Einladung und darf nicht in
  einen späteren Durchlauf für ein Konto übernommen werden.

Konto und Einladung teilen sich nie eine Keycloak-Sitzung. Um zwischen beiden zu wechseln, muss sich
der Nutzer abmelden.

## In der Demo

Ausgestellt wird eine Einladung auf der Seite „Einladungen“ von `/personenverzeichnis/`. Der Brief
liegt im Briefkasten der Demo. Auf der Anmeldeseite bietet die Demo eine Auswahl der offenen
Einladungen an, die Nummer und Kennwort einträgt (`invitations` im Demo-Block, ADR-28).
