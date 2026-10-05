# ADR-9: Profilabhängiger Token-Abruf — eigener OAuth2-Grant, den nur der Orchestrator aufrufen darf

**Status:** umgesetzt (DPoP-demo-xso); 2026-09-26 neu gefasst.

**Kontext**: Nach der Anmeldung braucht die App ein **AccessToken**. Das ist ein signierter Ausweis,
den sie bei Fachdiensten vorzeigt. Er nennt das Konto, das Sicherheitsniveau (`acr`) und die
benutzten Anmeldeverfahren (`amr`). Die App holt ihre Tokens nicht selbst bei Keycloak, sondern beim
**Orchestrator**, dem Server dieses Projekts. Ohne Keycloak (im Standardprofil) stellt der
Orchestrator ein simuliertes Token selbst aus. Mit dem Profil `keycloak` soll das Token echt von
Keycloak kommen. Daher kommt das Wort „profilabhängig“ im Titel. Keycloak hat aber nicht selbst
gesehen, wie sich der Nutzer in der App angemeldet hat. Es muss dem Orchestrator also glauben, wer
angemeldet ist und auf welchem Niveau. Die Frage ist, wie der Orchestrator ein solches Token bei
Keycloak anfordert, ohne dass jemand anderes denselben Weg missbrauchen kann. Die Begriffe erklärt
auch das [Glossar](../glossar/glossar.md).

Ein **OAuth2-Grant** ist eine festgelegte Art, bei einem Anmeldedienst wie Keycloak ein Token
anzufordern. Keycloak bringt mehrere solche Arten mit und lässt sich um eigene erweitern.

**Entscheidung**: Braucht der App-Kanal ein echtes AccessToken von Keycloak, stellt Keycloak es über
einen **eigenen OAuth2-Grant** aus (`urn:identity-demo:account-token`, umgesetzt in
`AccountTokenGrantType` im Modul `keycloak-extension`). Dafür gelten diese Regeln:

- **Nur der Orchestrator darf den Grant aufrufen.** Der Grant akzeptiert ausschließlich einen
  vertraulichen Client mit dem Attribut `identity-demo.account-token-grant`
  (`AccountTokenGrantClients`). Die Keycloak-Migration V4 setzt dieses Attribut am Client
  `orchestrator-app-token`. Jeder andere Client bekommt die Antwort `unauthorized_client`, auch der
  öffentliche Client des Browsers.
- **Der Orchestrator weist sich mit einer Signatur aus, nicht mit einem Geheimnis.** Der Client
  meldet sich per `private_key_jwt` an (siehe
  [ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md)). Dabei legt der
  Orchestrator eine signierte Client-Assertion vor, also ein kleines signiertes Dokument, und
  Keycloak prüft es gegen das JWKS des Orchestrators (die veröffentlichten öffentlichen Schlüssel).
  Jede Assertion ist 60 Sekunden gültig und enthält eine neue, zufällige `jti`
  (`OrchestratorClientAssertionSigner`). Dass dieselbe `jti` nicht zweimal angenommen wird, prüft
  Keycloak bei `private_key_jwt` selbst; Code dieses Projekts ist dafür nicht nötig. Das ist der
  Grundsatz „Signatur statt gemeinsames Geheimnis“ aus
  [ADR-7](ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md), hier angewendet auf die
  Richtung Orchestrator → Keycloak.
- **Konto, `acr` und `amr` kommen als Parameter** (`account_id`, `acr`, `amr`). Niveau und Nachweise
  legt allein der Orchestrator fest. Der Grant schreibt sie in die Notes der Keycloak-Sitzung, also in
  Zusatzwerte, die Keycloak zu einer Sitzung speichert. Von dort schreibt der
  `OrchestratorAcrAmrMapper` sie ins AccessToken.
- **Eine Sitzung je Anmeldung** (siehe
  [ADR-43](ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)). Der erste Aufruf öffnet
  die Keycloak-Sitzung. Jeder spätere Aufruf nennt sie als `session_id` und setzt genau diese Sitzung
  fort, etwa nach einem Step-up. Ist die Sitzung abgelaufen, legt der Grant keine neue an.
- **Ein Client ohne Rechte.** Der Client `orchestrator-app-token` hat keinerlei Rechte, ausdrücklich
  auch nicht die von `orchestrator-admin`.

**Annahme**: Keycloak holt das JWKS des Orchestrators über dessen `orchestratorBaseUrl`. Außerhalb
des Demomodus muss dieser Weg https mit geprüftem Zertifikat sein. Sonst könnte jemand, der diesen
Weg kontrolliert, eigene Schlüssel unterschieben und sich als Orchestrator anmelden. Ist das nicht
erfüllt, verweigert `ProductionModeCheck` den Start (siehe [07-betrieb.md](../07-betrieb.md)
Abschnitt 3c).

Weitere Einzelheiten stehen an anderer Stelle:

- Wann der Endpunkt `GET .../token` ein simuliertes und wann ein echtes Token liefert und wann er es
  erneuert oder neu ausstellt, beschreibt [05-api.md](../05-api.md) Abschnitt 3a („AccessToken“).
- Dass ein Step-up die zwischengespeicherten Tokens verwirft, regelt
  [ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md).
- Welche Werte Keycloak aus dem Konto liest, steht in [05-api.md](../05-api.md) Abschnitt 3b,
  „Keycloak liest die Konten – keine Spiegelung“.

**Erwogene Alternativen**:

- **Ein gemeinsames Admin-Secret.** Ein Service-Account holt per Token Exchange oder Impersonation
  direkt ein Token für einen beliebigen Nutzer. Verworfen, denn mit einem gestohlenen Secret ließe
  sich für jedes Konto ein Token ausstellen.
- **Zusätzlich eine Assertion je Konto** (so umgesetzt bis 2026-09-26). Der Orchestrator signierte
  jede Anfrage an den Grant mit einem eigenen Schlüsselpaar je Konto. Die Schlüssel lagen in
  `orchestrator.keycloak_keypair`, der öffentliche Schlüssel als Credential in Keycloak. Das Ziel
  war: „Ein verlorener Schlüssel betrifft höchstens ein Konto.“ Gestrichen wurde das, weil dieses Ziel
  nicht erreicht war. Alle Kontoschlüssel lagen im Klartext in derselben Datenbank wie der
  Client-Schlüssel des Orchestrators, und ein HSM ist nicht geplant. Wer an einen Schlüssel kam, kam
  also auch an alle anderen. Der Preis waren ein Datensatz je Konto, ein Credential je Keycloak-Nutzer
  und eine feste Reihenfolge: erst den Schlüssel erzeugen, dann den Grant zum ersten Mal aufrufen.
  Vor F-1 war diese Assertion die einzige Prüfung, weil der Grant den aufrufenden Client nicht
  prüfte. Seitdem stützt sich das Vertrauen auf den angemeldeten Client.
- **Nur die umgekehrte Richtung**: Keycloak ruft den Orchestrator auf, nie umgekehrt. Verworfen, weil
  `GET .../token` sofort antworten muss.

**Begründung**: Die entscheidende Prüfung betrifft den Client, nicht das Konto. Wer den
Client-Schlüssel des Orchestrators besitzt, könnte ohnehin für jedes Konto Tokens anfordern. Den Grant
braucht es nur bei der ersten Ausstellung oder wenn sich `acr` oder `amr` tatsächlich ändern. Sonst
erneuert Keycloak das Token mit seinem eigenen `refresh_token`.

**Grenze der DPoP-Bindung** (entschieden 2026-09-25, Review M-3). DPoP ist das Verfahren, mit dem die
App jede Anfrage mit einem Schlüssel signiert, der das Gerät nie verlässt. Die von Keycloak
ausgestellten Tokens sind aber Bearer-Tokens ohne `cnf.jkt`, also nicht an diesen Schlüssel
gebunden. Die DPoP-Bindung endet an `GET …/token`. Wer ein AccessToken abfängt, kann es bis zu seinem
Ablauf ohne Schlüssel benutzen. Gebunden bleibt aber die Sitzung: Neue Tokens gibt es nur mit
gültigem DPoP-Proof, und das RefreshToken verlässt das Backend nie (siehe
[09-dpop.md](../09-dpop.md) Abschnitt 4).

**Folgen und Kosten**: Der Grant ist ein Stück Keycloak-Erweiterung, das dieses Projekt selbst
geschrieben hat. Er baut auf der Schnittstelle aus `keycloak-server-spi-private` auf. Bei jedem
Keycloak-Update muss er deshalb mitgeprüft werden. Der Client-Schlüssel des Orchestrators
(`orchestrator.node_signing_key`) liegt in der Demo im Klartext in der Datenbank (siehe
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)).

**Geschichte**: Die übrigen Clients mit `private_key_jwt` statt mit `client_secret` anzumelden, war
hier als spätere Härtung zurückgestellt. Umgesetzt ist das mit
[ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md). Früher beschrieb diese ADR
auch den Profilschalter und die Attribute, die damals nach Keycloak gespiegelt wurden. Beides steht
jetzt in den oben genannten Kapiteln.
