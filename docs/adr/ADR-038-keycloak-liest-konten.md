# ADR-38: Keycloak liest die Konten, statt sie zu spiegeln

**Status:** entschieden und umgesetzt (2026-09-25).
Löst die Spiegelung ab, die [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)
(Public Key als Credential am Nutzer) und [ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)
(Änderungen per Event „bis Keycloak“) voraussetzten.

> **Nachtrag 2026-09-26:** Das Schlüsselpaar je Konto gibt es nicht
> mehr ([ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
> Der Grant holt keinen Public Key mehr beim Orchestrator. Er nimmt `account_id`, `acr` und `amr`
> als Parameter entgegen, und zwar nur vom vertraulichen Client `orchestrator-app-token`. Die
> Festlegung zum Public Key weiter unten ist entsprechend angepasst.

> **Nachtrag 2026-10-03:** Keycloak prüft ein Passwort, ändert es aber nie. Dafür gab es zwei
> Gründe. Keycloaks Funktion „Passwort ändern“ fragte das alte Passwort nicht ab. Sie hätte damit
> aus einer `loa1`-Sitzung den zweiten Faktor für `loa2` beschafft. Und hätte die Federation die
> Änderung abgelehnt, hätte Keycloak das Passwort bei sich gespeichert. Deshalb gilt jetzt:
>
> - Die Federation lehnt jede Änderung ab (`ReadOnlyException`).
> - Das Realm schaltet Keycloaks eigene Required Actions ab (`V7__locked_down_defaults`). Das sind
>   Schritte, die Keycloak einem Nutzer bei der Anmeldung abverlangt, etwa ein neues Passwort.
> - Ein Passwort ändert nur die Verwaltung der Verfahren im Orchestrator.
>
> Damit entfällt auch „Passwort zurücksetzen“ in der Admin-Konsole.

**Entscheidung**: [Keycloak](../glossar/glossar.md) ist das Produkt, das auf der Website die
Anmeldung führt und die Tokens ausstellt. Dafür braucht Keycloak Nutzer. Die Konten führt aber der
Orchestrator, der Server dieses Projekts. Die Frage war: Hält Keycloak eine eigene Kopie der Konten
(Spiegelung), oder fragt es beim Orchestrator nach?

Entschieden ist: Die Konten des Orchestrators *sind* die Nutzer von Keycloak. Keycloak bindet sie
über eine Nutzer-Federation ein, also eine Erweiterung, die Nutzer aus einer fremden Quelle liefert.
Diese Federation in der Extension (`OrchestratorStorageProvider`) liest ein Konto bei Bedarf beim
Orchestrator nach und importiert es nicht. Keycloak hält keine Kopie von Identität, E-Mail-Adresse,
Namen oder Stammdaten. In seinem föderierten Speicher liegt nur, was Keycloak für sich selbst
braucht: Sitzungen, Fehlversuche und Zustimmungen.

**Warum**: Bei zehn Millionen Konten und mehr ist Spiegeln der teure Weg:

- Jede Änderung an einem Konto löste einen Aufruf der Admin-API von Keycloak aus. Ein vollständiger
  Abgleich brauchte einen Aufruf je Konto.
- Zwei Datenbestände galten beide als maßgeblich und mussten übereinstimmen. Dafür brauchte es:
  - Konfliktregeln für E-Mail-Adressen (S-2),
  - eine Suche per Attribut über alle Nutzer,
  - einen zweiten Schreibweg in der Extension (`findOrCreateUser`),
  - das Hochladen des Public Keys an zwei Stellen.
- Lesen kostet dagegen nur, wenn sich jemand anmeldet. Es ist ein einzelner Zugriff über den
  Primärschlüssel oder über den eindeutigen E-Mail-Anker, unabhängig von der Zahl der Konten.

**Festlegungen**:

- **Feste Komponenten-Id**, eine UUID (`USER_STORAGE_COMPONENT_ID`, Migration V2). Keycloak bildet
  daraus die Nutzer-Id `f:<UUID>:<accountId>` und damit das `sub` jedes Tokens, also die Kennung
  des Nutzers im Token. Es ist eine UUID, weil Keycloak für föderierte Nutzer dieses Format erwartet.
  Sonst warnt Keycloak beim Speichern jeder Sitzung (`KeyUtils`, „future migration might fail“).
  Bis 2026-09-29 hieß die Id `orch-accounts`. Eine Id, die bei jedem Aufbau des Realms neu vergeben
  wird, würde jedes `sub`, jede Zustimmung und jede Sitzung ändern.
- **Die Nutzer-Id ist die Konto-Id, nicht der Benutzername.** Der Benutzername ist die bestätigte
  E-Mail-Adresse (ohne sie `account-<id>`) und ändert sich mit ihr. Das `sub` darf sich nicht ändern.
- **Cache höchstens 60 Sekunden** (`MAX_LIFESPAN`): Je Nutzer gibt es höchstens einen Aufruf pro
  Minute. Änderungen an Namen oder Adresse sind spätestens nach einer Minute sichtbar.
- **Schreibgeschützt:** Was zum Konto gehört, lässt sich in Keycloak nicht ändern
  (`ReadOnlyException`). Ein Schreibweg an Keycloak vorbei würde wieder einen zweiten maßgeblichen
  Datenbestand schaffen.
- **Keine Liste:** Eine Suche findet genau einen Nutzer, über den exakten Benutzernamen, die
  E-Mail-Adresse oder die Konto-Id. Keycloak fragt jede Federation nach jedem Namen. Das gilt auch
  für den Namen eines Einladungs-Nutzers (ADR-48) oder für das, was jemand in die Passwortmaske
  tippt. Ein Name, der weder `account-<Id>` noch eine E-Mail-Adresse ist, gilt deshalb als
  unbekannt (`404`) und nicht als fehlerhafte Anfrage.
- **Kein Schlüssel je Konto in Keycloak.** Bis 2026-09-26 las der Grant den Public Key eines Kontos
  jedes Mal frisch beim Orchestrator, statt ihn nach Keycloak hochzuladen. Seit das Schlüsselpaar
  weggefallen ist (ADR-9), prüft der Grant nur noch den aufrufenden Client.
- **Löschen ist das einzige Ereignis**, das Keycloak noch erreicht. Keycloaks eigene Daten zu einem
  gelöschten Konto räumt ein eigener Admin-Endpunkt ab (`AccountRemoval`). Keycloaks
  `DELETE users/{id}` taugt dafür nicht: Es würde den Nutzer zuerst nachschlagen und ein gelöschtes
  Konto nicht mehr finden.
- **Ein nicht erreichbarer Orchestrator ist ein Fehler**, kein „Nutzer unbekannt“. Sonst sähe ein
  Ausfall aus wie ein falscher Benutzername.

**Erwogene Alternativen**:

- **Spiegeln beibehalten, nur härten** (eindeutiges Attribut, Konfliktregeln): verworfen. Die Kosten
  wachsen mit der Zahl der Konten. Außerdem ist jede Änderung am Abgleich ein möglicher Weg, ein
  fremdes Konto zu übernehmen.
- **Federation mit Import** (Keycloak legt beim ersten Lesen eine lokale Kopie an): verworfen. Das
  wäre wieder eine Kopie, nur später angelegt.
- **Kein Cache**: verworfen. Das hieße mehrere Aufrufe beim Orchestrator je Anmeldung. Bei
  Lastspitzen wäre das teuer, ohne Gewinn gegenüber einer Minute Frische.

**Folgen**:

- Beim Umstieg ändert sich das `sub` aller Nutzer einmal, denn die bisherigen Spiegel verschwinden
  mit der alten Komponente. Bestehende Keycloak-Sitzungen verfallen. Bei einem Bestand von
  Millionen Nutzern ist das ein geplanter Migrationstermin.
- Der Orchestrator muss für jede Anmeldung bei Keycloak erreichbar sein. Das musste er auch vorher,
  denn der Anmeldeablauf fragt ihn ohnehin.
- Folgendes entfällt: `KeycloakAccountSyncService`, der vollständige Abgleich in der
  Admin-Oberfläche, das Hochladen des Public Keys und die S-2-Konfliktregeln für Spiegel.
