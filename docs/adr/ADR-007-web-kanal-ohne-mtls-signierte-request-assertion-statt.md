# ADR-7: Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat

**Status:** umgesetzt.

**Kontext**: Auf der Website führt **Keycloak** die Anmeldung. Keycloak ist das Produkt, das die
Anmeldeseiten zeigt und am Ende die Tokens ausstellt. Welche Schritte ein Nutzer durchlaufen muss,
fragt Keycloak beim **Orchestrator** nach, dem Server dieses Projekts. Diese Verbindung heißt
**Web-Kanal** (siehe [Glossar](../glossar/glossar.md)). Der Orchestrator muss sich darauf verlassen
können, dass eine Anfrage wirklich von Keycloak kommt. Umgekehrt muss Keycloak sicher sein, dass die
Antwort wirklich vom Orchestrator stammt. Denn diese Antwort entscheidet, wer angemeldet wird. Diese
ADR legt fest, wie sich die beiden Server gegenseitig ausweisen.

Zwei Begriffe vorweg:

- **mTLS** (gegenseitiges TLS) heißt: Beide Server zeigen sich schon beim Aufbau der Verbindung ein
  Zertifikat und prüfen das der Gegenseite.
- Eine **Assertion** ist hier ein kleines, signiertes Dokument (ein JWT), das Keycloak jeder Anfrage
  beilegt. Die Signatur beweist, dass das Dokument von Keycloak kommt und unterwegs nicht verändert
  wurde.

**Entscheidung**: Die Verbindung von Keycloak zum Orchestrator im Web-Kanal, also von Server zu
Server, wird **ohne mTLS** abgesichert. Keycloak legt jeder Anfrage eine signierte Assertion bei, und
der Orchestrator prüft sie (`PeerAuthValidator`, siehe [05-api.md](../05-api.md) Abschnitt 3b). Der
Browser erreicht den Orchestrator an keiner Stelle direkt.

**Die Antwort ist ebenso signiert** (seit 2026-09-25, Review M-9). Die Assertion sichert nur die
Anfrage. Die Antwort aber entscheidet, wer eingeloggt wird. Deshalb signiert der Orchestrator jede
Antwort auf eine Peer-Auth-Anfrage, also auf eine Anfrage, mit der Keycloak sich ausgewiesen hat. Das
übernimmt der `KeycloakResponseSigner`; die Signatur steht im Header
`Orchestrator-Response-Signature`. Sie deckt Status und Inhalt der Antwort ab und ist an die `jti`
der Anfrage gebunden, also an deren eindeutige Kennung. Die Keycloak-Erweiterung prüft die Signatur
gegen `/orchestrator/api/v1/kc/response-jwks/.well-known/jwks.json`, bevor sie der Antwort vertraut
(`OrchestratorResponseVerifier`).

Der Orchestrator signiert nur die Antwort auf eine Assertion, die er vorher angenommen hat. Dafür
prüft er Signatur, Aussteller, Empfänger, Adresse und Alter der Assertion
(`PeerAuthValidator.verify`). Ohne diese Bedingung bekäme jeder eine vom Orchestrator signierte
Antwort, in der `req`, `iss` und `aud` frei gewählt wären. Die Antwort auf eine abgelehnte Assertion
ist deshalb unsigniert. Die Erweiterung behandelt sie wie jede Antwort, die sie nicht prüfen kann,
als Fehler.

Je Anfrage gibt es genau ein JWT. Es gibt also nicht zusätzlich ein Access-Token und daneben einen
getrennten Proof. Der Grund: Bei der ersten Anmeldung gibt es noch kein `sub`, also noch keine
Kennung des Nutzers. Die Assertion sagt deshalb nur: „Ich handle für diese Kanalbindung; der Nutzer
ist vielleicht noch unbekannt.“ Die **Kanalbindung** ist die Kennung, mit der Keycloak eine Anfrage
einem bestimmten Anmeldevorgang zuordnet.

Keycloak signiert mit einem **eigenen Schlüsselpaar** der Keycloak-Erweiterung. Es sind nicht die
Schlüssel, mit denen der Realm Tokens signiert. (Ein **Realm** ist ein abgeschlossener Bereich in
Keycloak mit eigenen Nutzern und Einstellungen.) Die Erweiterung veröffentlicht den öffentlichen
Schlüssel unter `/realms/{realm}/orchestrator-jwks/.well-known/jwks.json`
(`OrchestratorJwksResourceProvider`). Der Orchestrator holt ihn von dort.

**Grundsatz: Signatur statt gemeinsames Geheimnis.** Wo zwei Server einander vertrauen müssen,
beweist die eine Seite ihre Identität mit einer Signatur. Den öffentlichen Schlüssel dazu ruft die
andere Seite über eine JWKS-URL ab, also eine Adresse, unter der die öffentlichen Schlüssel
veröffentlicht sind. Ein gemeinsames Geheimnis, das beide Seiten kennen und das man verteilen und
schützen müsste, gibt es nicht. Diese ADR legt den Grundsatz für die Richtung Keycloak → Orchestrator
fest. Dieselbe Regel gilt auch anderswo:

- [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md) wendet
  sie auf die Richtung Orchestrator → Keycloak beim Ausstellen von Tokens an.
- [ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md) wendet sie auf die
  Verwaltung des Realms an.

**Erwogene Alternativen**:

- **mTLS** zwischen Keycloak und Orchestrator: Beide Seiten prüfen schon beim Verbindungsaufbau das
  Zertifikat der jeweils anderen.
- **Token Exchange**: unnötig. Keycloak führt die Sitzung ohnehin und kann die Assertion im eigenen
  Prozess ausstellen.
- **Keycloak hält stellvertretend für das Gerät einen DPoP-Schlüssel**: verworfen. DPoP ist ein
  Verfahren, bei dem ein Client jede Anfrage mit einem eigenen Schlüssel signiert. Sein Wert liegt
  darin, dass dieser Schlüssel nicht exportierbar auf einem Client liegt, dem man nicht vertraut.
  Liegt der Schlüssel auf einem Server, ist er praktisch ein gemeinsames Geheimnis, nur mit mehr
  Aufwand. Ein solcher Schlüssel je Nutzer hätte außerdem schwere Folgen: Die Geräteverknüpfung
  (`DeviceAccountLink`) würde dann bei jeder Anmeldung im Web ein bekanntes Gerät erkennen.

**Begründung**: mTLS bringt Aufwand im Betrieb. Für zwei Serverdienste müssen Zertifikate verteilt,
regelmäßig erneuert und bei Bedarf widerrufen werden. Eine signierte Assertion braucht das nicht;
auch ihr Schlüssel wird über eine JWKS-URL bereitgestellt. Ein eigenes Schlüsselpaar trennt die
Signatur zwischen den Servern von der Signatur der Tokens. Weil der Browser den Orchestrator nie
direkt erreicht, beschränkt sich die Angriffsfläche auf die eine Verbindung zwischen den beiden
Servern.

**Folgen und Kosten**: Die Sicherheit dieser Verbindung beruht ganz darauf, dass die Anwendung die
Signaturen prüft, und zwar in beide Richtungen.

**Verschlüsselt ist die Verbindung nicht:** Sie läuft heute über `http://`. Wer mitliest, sieht
Anfragen und Antworten (etwa E-Mail-Adressen). Fälschen kann er sie aber nicht, und er kann sie auch
keiner anderen Anfrage unterschieben. Denn die Assertion bindet die ganze Anfrage: die Methode, die
Adresse samt Query (`htu`) und den Body (`body_sha256`). Die Antwortsignatur tut dasselbe für die
Antwort. TLS auf dieser Verbindung ist Sache der Umgebung (ADR-35, Bereich 3). Im OpenShift-Pod läuft
die Verbindung ohnehin über `localhost`. Mit mTLS wären Identität und Verschlüsselung schon beim
Verbindungsaufbau erzwungen.

Wer Keycloak übernimmt, kann jeden Nutzer nachahmen. Das liegt aber schon daran, dass jede Anmeldung
im Web zuerst durch Keycloak läuft. Auch mTLS würde daran nichts ändern.

**Geschichte**: Ursprünglich war angenommen, die Assertion werde mit den Token-Schlüsseln des Realms
geprüft. Umgesetzt wurde aber von Anfang an ein eigenes Schlüsselpaar. Eine Zeit lang gab es eine
Ausnahme vom Grundsatz „kein direkter Zugriff aus dem Browser“: Der `KcMeController` las für die
Testoberfläche den eigenen Journey-Trace mit einem echten AccessToken. Seit der Journey-Trace nur
noch auf der Admin-Seite steht, ist diese Ausnahme entfernt (2026-09-23).
