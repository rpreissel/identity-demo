# ADR-47: Ein Tool, das die Anmeldung verlässt, kehrt im Web-Kanal auf die Action-URL des laufenden Schritts zurück

**Status:** umgesetzt 2026-09.

**Entscheidung.** Manche Tools schicken den Nutzer während der Anmeldung auf eine fremde Website. Das
Tool `ident-nect` etwa leitet ihn zu [Nect](../glossar/glossar.md) weiter, einem externen Dienst, der
Personen anhand von Ausweis und Selfie identifiziert. Danach muss der Nutzer wieder in den laufenden
Anmeldeablauf von Keycloak zurückkommen. Diese ADR legt fest, über welche Adresse das im
[Web-Kanal](../glossar/glossar.md), also auf der Website, geschieht.

`ident-nect` schickt den Nutzer im Web-Kanal zu Nect und bekommt ihn über die **Action-URL** des
laufenden Keycloak-Schritts zurück. Die Action-URL ist die Adresse, an die die Anmeldeseite ihr
Formular schicken würde. Sie enthält die Parameter `session_code`, `execution`, `client_id` und
`tab_id`. Der Ablauf sieht so aus:

1. Die Keycloak-Erweiterung des Projekts nennt diese Adresse beim Aktivieren des Tools als
   `returnUri`.
2. Nect hängt beim Rücksprung `nectCaseId` an die Adresse an.
3. Keycloak behandelt den GET-Aufruf mit gültigem Code so, als hätte der Nutzer das Formular des
   Schritts abgeschickt. Die Parameter der Anfrage sind die Eingabe des Tools.

Der Orchestrator nimmt eine Rücksprungadresse nur an, wenn sie mit einem konfigurierten Präfix
beginnt. Er merkt sie sich am Fall.

Keycloaks Aktionscode (`session_code`) gilt nur einmal. Ein `retry`, also ein erneuter Versuch im
Web-Kanal, nennt deshalb eine frische Action-URL. Die Erweiterung schickt sie als `returnUri` im
PATCH mit; sie stammt aus `WebToolRendererFactory.actionFields`. Diese neue Adresse durchläuft
dieselbe Prüfung und ersetzt die gemerkte. Fehlt sie, bleibt die gemerkte Adresse gültig; das ist der
Fall im App-Kanal.

Berichtigt 2026-09-30 (vierte Bewertung, K-2): Zuvor stand hier, ein `retry` behalte die Adresse der
Aktivierung. Deren Code war zu diesem Zeitpunkt aber schon verbraucht.

**Warum.** Bei einer Registrierung gibt es während des Identifizierungsschritts noch keinen Nutzer.
Das Konto entsteht erst in der [Journey](../glossar/glossar.md), dem geführten Ablauf des
Orchestrators, und Keycloak legt selbst keine Nutzer an (ADR-38). Der Weg zurück muss also ohne
Nutzer funktionieren. Außerdem soll er nur benutzen, was Keycloak selbst vorsieht.

Die Action-URL erfüllt beides:

- Sie gehört zur **Auth-Session**, also zu dem Zwischenstand, den Keycloak für eine laufende
  Anmeldung führt. Ohne das Session-Cookie des Browsers ist sie wertlos.
- Ihr Code ist genau das, was Keycloak selbst im `state`-Parameter an fremde Identity Provider
  weitergibt. Ein Identity Provider ist ein externer Dienst, bei dem sich Nutzer anmelden können.

Die Auth-Session bleibt beim Rücksprung dieselbe. Damit bleiben auch Tab-ID, Kanal-ID und
`channel_binding` erhalten. Es muss nichts wiederhergestellt werden.

**Erwogene Alternativen** (Befunde aus den Quellen von Keycloak 26.6.4):

- **Identity Brokering mit einem OIDC-Adapter vor Nect.** Beim Identity Brokering überlässt Keycloak
  die Anmeldung einem externen Identity Provider; ein Adapter hätte Nect dafür über OIDC, das übliche
  Anmeldeprotokoll, angebunden. Verworfen, aus diesen Gründen:
  - Ein Broker-Login ist immer die Anmeldung eines Nutzers. Ohne Federated-Identity-Link, also ohne
    gespeicherte Verknüpfung zwischen der externen Identität und einem Keycloak-Nutzer, setzt Keycloak
    den Ablauf zurück: alle Auth-Notes, den Ausführungsstand und den Nutzer.
  - Der First-Broker-Login-Flow muss mit einem Nutzer enden, und danach schreibt Keycloak immer einen
    solchen Link. Der ursprüngliche Browser-Flow läuft nie weiter.
  - Der Link widerspricht ADR-18, weil er eine Nect-Identität an ein Konto bindet. Bei stabiler
    Kennung `sub` (Online-Ausweis) würde der nächste Nect-Login den Nutzer direkt als dieses Konto
    anmelden, ohne Journey. Bei wechselndem `sub` (Reisepass) entstünde je Identifizierung ein neuer
    Link. Der zweite Link desselben Kontos zum selben Identity Provider endet mit dem Fehler
    „already linked“.
- **Transient Users** (Nutzer, die Keycloak nur für die Dauer einer Sitzung kennt) als Lösung ohne
  echten Nutzer. Verworfen: Die Funktion ist als EXPERIMENTAL markiert. Außerdem scheitert ein
  späterer Tausch gegen das Konto des Orchestrators, weil Keycloak beim Setzen eines anderen Nutzers
  den Fehler `USER_CONFLICT` wirft.
- **Action Tokens**, also die Links, mit denen Keycloak etwa ein Passwort zurücksetzt oder eine
  E-Mail-Adresse bestätigt. Verworfen: Keycloak prüft vor jeder Verarbeitung, dass der im Token
  genannte Nutzer existiert.
- **Ein eigener Rücksprung-Endpunkt** nach dem Muster der QR-Warteseite (ADR-45), der die
  Auth-Session über Cookie und Tab-ID sucht. Verworfen: Das wäre ein zusätzlicher schreibender
  Endpunkt außerhalb des üblichen Ablaufs, und die Action-URL macht ihn überflüssig.

**Folgen und Kosten.**

- Der Rücksprung muss im selben Browser stattfinden und vor Ablauf von Keycloaks Login-Timeout. So
  lange gilt der Code.
- Wer den Aufruf wiederholt, schickt einen schon verbrauchten Code. Keycloak zeigt dann die aktuelle
  Seite noch einmal.
- Die Rücksprungadresse ist Teil des Tool-Starts (`POST .../tools/ident-nect`, optionaler Body). Der
  zulässige Präfix ist Teil der Konfiguration (`ident-nect.return-uri-prefixes`); im Profil
  `keycloak` ist das die öffentliche Keycloak-Adresse.
- Es bleibt ein einziger Nect-Port für beide Kanäle, also eine einzige Schnittstelle zu Nect. Die
  echte Anbindung an Nect betrifft nur diesen Port.
