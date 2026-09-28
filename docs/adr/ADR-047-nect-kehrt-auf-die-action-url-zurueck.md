# ADR-47: Ein Tool, das die Anmeldung verlässt, kehrt im Web-Kanal auf die Action-URL des laufenden Schritts zurück

**Status:** umgesetzt 2026-09.

**Entscheidung.** `ident-nect` schickt den Nutzer im Web-Kanal zu Nect und bekommt ihn über die
Action-URL des laufenden Keycloak-Schritts zurück: dieselbe Adresse, an die die Seite ihr Formular
schicken würde, mit `session_code`, `execution`, `client_id` und `tab_id`. Die Erweiterung nennt sie
beim Aktivieren als `returnUri`, Nect hängt `nectCaseId` an, und Keycloak behandelt den GET mit
gültigem Code wie den Formularversand des Schritts. Die Parameter der Anfrage sind die Eingabe des
Tools. Der Orchestrator nimmt eine Rücksprungadresse nur unter einem konfigurierten Präfix an und
merkt sie sich am Fall, damit ein `retry` sie behält.

**Warum.** Ein Identifizierungsschritt bei der Registrierung hat noch keinen Nutzer; das Konto
entsteht erst in der Journey, und Keycloak legt keine Nutzer an (ADR-38). Der Weg zurück muss also
ohne Nutzer auskommen, und er soll nur benutzen, was Keycloak selbst vorsieht. Die Action-URL erfüllt
beides: Sie ist an die Auth-Session gebunden, ohne das Session-Cookie des Browsers wertlos, und ihr
Code ist genau das, was Keycloak selbst im `state`-Parameter an fremde Identity Provider gibt. Die
Auth-Session bleibt dieselbe, damit auch Tab-ID, Kanal-ID und `channel_anchor`; nichts ist zu retten.

**Erwogene Alternativen** (Befunde aus den Quellen von Keycloak 26.6.4, Herleitung in
[ideen/ident-nect.md](../ideen/ident-nect.md)):

- **Identity Brokering mit einem OIDC-Adapter vor Nect.** Verworfen. Ein Broker-Login ist immer
  die Anmeldung eines Nutzers: Ohne Federated-Identity-Link setzt Keycloak den Ablauf zurück
  (alle Auth-Notes, der Ausführungsstand, der Nutzer), der First-Broker-Login-Flow muss mit einem
  Nutzer enden, und danach schreibt Keycloak immer einen Link. Der ursprüngliche Browser-Flow läuft
  nie weiter. Der Link widerspricht ADR-18, weil er eine Nect-Identität an ein Konto bindet.
- **Action Tokens** (die Links für Passwort zurücksetzen und E-Mail bestätigen). Verworfen: Keycloak
  prüft vor jedem Handler, dass der im Token genannte Nutzer existiert.
- **Ein eigener Rücksprung-Endpunkt** nach dem Muster der QR-Warteseite (ADR-45), der die
  Auth-Session über Cookie und Tab-ID sucht. Verworfen: ein schreibender Sonderweg, den die
  Action-URL überflüssig macht.

**Folgen und Kosten.** Der Rücksprung muss im selben Browser stattfinden und vor Keycloaks
Login-Timeout, so lange gilt der Code. Wer den Aufruf wiederholt, trifft auf einen verbrauchten
Code, und Keycloak zeigt die aktuelle Seite noch einmal. Die Rücksprungadresse ist Teil des
Tool-Starts (`POST .../tools/ident-nect`, optionaler Body) und der Präfix Teil der Konfiguration
(`ident-nect.return-uri-prefixes`, im Profil `keycloak` die öffentliche Keycloak-Adresse). Der
Nect-Port bleibt einer für beide Kanäle; die echte Anbindung trifft nur ihn.
