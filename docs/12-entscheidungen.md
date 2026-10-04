# Architekturentscheidungen

Hier stehen die großen Entscheidungen dieses Projekts, jeweils mit der ernsthaft erwogenen
Alternative und dem Preis der gewählten Lösung. Wie die Lösung *aussieht*, beschreiben die
verlinkten Kapitel; hier steht nur das *Warum*.

---

## Die Entscheidungen

Jede Entscheidung hat eine eigene Datei unter [`adr/`](adr/).

| ADR | Entscheidung |
|---|---|
| [ADR-1](adr/ADR-001-ein-controller-je-tool-kein-generischer-dispatcher.md) | Ein Controller je Tool, kein generischer Dispatcher |
| [ADR-2](adr/ADR-002-zustand-statt-vererbung-bei-authjourney.md) | Zustand statt Vererbung bei `AuthJourney` |
| [ADR-3](adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md) | `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink` |
| [ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md) | Zwei Obergrenzen für das Sicherheitsniveau |
| [ADR-6](adr/ADR-006-next-als-reine-adresse-feste-routing-tabelle-statt.md) | `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS |
| [ADR-7](adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md) | Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat |
| [ADR-8](adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md) | Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen |
| [ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md) | Profilabhängiger Token-Abruf — eigener OAuth2-Grant, den nur der Orchestrator aufrufen darf |
| [ADR-10](adr/ADR-010-interessent-ist-konto-zustand-kein-eigener-authintent.md) | Interessent ist Konto-Zustand, kein eigener AuthIntent |
| [ADR-11](adr/ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md) | Ein Anker, der schon einem anderen Konto gehört, wird abgewiesen; Konten werden nie automatisch zusammengeführt |
| [ADR-12](adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md) | Ein Widerruf ist eine eigene Zeile mit eigener Quelle |
| [ADR-14](adr/ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md) | Das Konto als gemeinsame Sperre, jeder Fakt an genau einer Stelle |
| [ADR-15](adr/ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md) | Nachweise und ausgestellte Tokens in getrennten Tabellen |
| [ADR-16](adr/ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) | Ein Schema und ein Migrationsordner je Modul |
| [ADR-17](adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md) | Adresse bestätigen und E-Mail-Login einrichten sind zwei Schritte |
| [ADR-18](adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md) | Bestätigen und Zuordnen sind zwei Schritte |
| [ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md) | Konten werden nur über Anker gefunden — auch die `restricted_id` der eID ist einer |
| [ADR-20](adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md) | Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen |
| [ADR-21](adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md) | KOBIL-Anbindung — PIN im Backend, Nachweis über eine Einmalkennung |
| [ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md) | Demo-Geheimnisse liegen im Klartext — benannt statt verschwiegen |
| [ADR-24](adr/ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md) | Ein Verfahren hängt von einem anderen ab, indem es dessen Angabe verlangt |
| [ADR-25](adr/ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md) | Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung |
| [ADR-26](adr/ADR-026-api-vertrag-wird-generiert.md) | Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt |
| [ADR-27](adr/ADR-027-gemeinsame-typen-im-kernel-paket.md) | Gemeinsame Typen liegen im Paket `orchestrator.kernel` *(heute `orchestrator.domain`, [ADR-40](adr/ADR-040-fachkern-im-paket-domain.md))* |
| [ADR-28](adr/ADR-028-demo-werte-abschaltbar.md) | Demo-Werte lassen sich abschalten |
| [ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md) | Die Event Publication Registry von Spring Modulith statt einer eigenen Outbox-Tabelle |
| [ADR-31](adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md) | Der Freischaltcode liegt im Personenverzeichnis, `ident_fsc` fragt es über einen Port |
| [ADR-32](adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md) | Tool-Sperre und Reihenfolge je Kanaltyp |
| [ADR-33](adr/ADR-033-texte-als-vorlage-im-code.md) | Texte als deutsche Vorlage im Code, ausgeliefert als Referenz, formuliert per Prompt |
| [ADR-34](adr/ADR-034-personenverzeichnis-meldet-aenderungen.md) | Personenverzeichnis – Partnernummer, drei Rollen, Änderungen per Event ans Konto |
| [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md) | Betriebsanspruch – der Backend-Kern ist produktionsreif, Rand und Umgebung folgen später |
| [ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md) | Niveaus und ihre Nachweise – was nur behauptet ist, läuft nur im Demomodus |
| [ADR-37](adr/ADR-037-postfach-traegt-unidentifizierte-konten.md) | Bei einem nie identifizierten Konto genügt das Postfach auch für destruktive Aktionen |
| [ADR-38](adr/ADR-038-keycloak-liest-konten.md) | Keycloak liest die Konten, statt sie zu spiegeln |
| [ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md) | Was eine Kontolöschung überlebt – das Änderungsprotokoll ohne Werte |
| [ADR-40](adr/ADR-040-fachkern-im-paket-domain.md) | Der fachliche Kern liegt im Paket `domain`, ohne Framework, per ArchUnit geprüft |
| [ADR-41](adr/ADR-041-keycloakify-neben-freemarker.md) | Keycloakify läuft neben FreeMarker, der Orchestrator schaltet realmweit um |
| [ADR-42](adr/ADR-042-loa1-anmeldung-umschalten.md) | Ein Browser-Client, der Orchestrator schaltet die Anmeldung auf `loa1` um |
| [ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md) | Ein angemeldeter Kanal hat genau eine Keycloak-Sitzung und lebt nicht länger als sie |
| [ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md) | Das Zählwerk liegt im Orchestrator, die Regeln in den Modulen |
| [ADR-45](adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md) | Die QR-Warteseite fragt im Hintergrund und schickt ihr Formular nur einmal |
| [ADR-46](adr/ADR-046-konto-im-aufbau.md) | Ein Konto ist im Aufbau, bis es ein Anmeldeverfahren hat |
| [ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md) | Ein Tool, das die Anmeldung verlässt, kehrt im Web-Kanal auf die Action-URL des laufenden Schritts zurück |
| [ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md) | Vorgangszugang mit Einmalkennwort – die Einladung ist ein Keycloak-Nutzer eigener Art, nie ein Konto |
| [ADR-49](adr/ADR-049-arbeitsdaten-der-tools-am-orchestrator.md) | Die Arbeitsdaten der Tools liegen als JSON an der Tool-Sitzung des Orchestrators |
| [ADR-50](adr/ADR-050-api-versionierung-umschlag-und-tool.md) | API-Versionierung auf zwei Ebenen: ein Bruch am Umschlag braucht eine neue Version, ein Bruch an einem Tool trifft nur Clients, die es nennen; `/kc` wird nicht eingefroren |
| [ADR-51](adr/ADR-051-versionen-als-pfadsegment.md) | Versionierung: Orchestrator und Tools einzeln, beide per Pfadsegment (`/orchestrator/api/v1`, `/tools/api/<toolId>/v<N>`); der Client nennt je Tool eine Fassung (`enroll-sms@1`), eine neue Orchestrator-Version ist ein Pflichtupdate; wann ein Tool eine neue Fassung braucht und was die alte dann tut |

ADR-4, 13, 23 und 30 sind in anderen Entscheidungen aufgegangen (4 in 14 und 16, 13 in
`db/migration/KONVENTIONEN.md`, 23 in 21, 30 in 16).

Erkannte, bewusst zurückgestellte Verbesserungen stehen in
[offene-befunde.md](offene-befunde.md) Abschnitt 8.
