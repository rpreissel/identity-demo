# ADR-15: Nachweise und ausgestellte Tokens in getrennten Tabellen

**Status:** umgesetzt.

**Kontext**: Der Orchestrator, also der Server dieses Projekts, merkt sich für jeden **Kanal** (eine
Verbindung über App oder Website), was der Nutzer dort bewiesen hat. Das ist der **Nachweis**, etwa
„Passwort richtig vor 5 Minuten, SMS-Code richtig vor 2 Minuten“. Daraus berechnet er das
Sicherheitsniveau. Für die App stellt er außerdem **Tokens** aus, also signierte Ausweise, die die
App bei Fachdiensten vorzeigt. Die Begriffe erklärt auch das [Glossar](../glossar/glossar.md). Die
Frage ist, ob Nachweise und Tokens in derselben Tabelle stehen oder getrennt.

**Entscheidung**: Es gibt zwei Tabellen:

- `orchestrator.session_evidence` hält fest, was auf einem Kanal bewiesen wurde.
- `orchestrator.app_token_session` hält fest, welche Tokens daraus ausgestellt wurden.

Die Abhängigkeit geht nur in eine Richtung: `orchestrator.app_token_session.session_evidence_id`
zeigt auf die Nachweise, nie umgekehrt. Mehrere Token-Kontexte dürfen auf dieselben Nachweise
zeigen; `AppTokenSessionRepository.findBySessionEvidenceId` liefert deshalb eine Liste. Abgeleitete
Werte stehen in keiner der beiden Tabellen. So berechnet `AuthPolicy.resolveAcr` das aktuelle Niveau
(`currentAcr`) bei jedem Lesen neu aus `methods` (siehe [Domänenmodell](../02-domaenenmodell.md)
Abschnitt 7).

**Erwogene Alternative**: Eine einzige Tabelle, in der die Spalten für die Tokens in derselben Zeile
neben den Nachweisen stehen. So war es vor dem Zugang über Keycloak (`07e7156`).

**Warum diese**: Es gibt zwei Gründe. Keiner davon beruht darauf, wie viele Zeilen einander
zugeordnet sind.

1. **Nicht jeder Kanal hat Tokens, aber jeder hat Nachweise.** Der Web-Kanal legt nie eine
   `AppTokenSession` an (siehe [API](../05-api.md) Abschnitt 3a). In einer gemeinsamen Tabelle hätte
   jede Zeile des Web-Kanals vier Spalten für Tokens, die dauerhaft leer blieben.
2. **Die Nachweise sind die maßgebliche Angabe, die Tokens nur ein Zwischenspeicher.** Darauf beruht
   `SessionEvidenceService.invalidateCachedTokens`: Bei einem Step-up, also wenn ein angemeldeter
   Nutzer ein höheres Niveau nachweist, setzt der Server Access- und RefreshToken auf `null`.
   **Die Nachweise bleiben dabei erhalten.**

**Kosten**: Die beiden Tabellen ähneln sich äußerlich stark. Beide haben `account_id`, `version` und
`updated_at`, und im APP-Kanal entstehen sie fast immer gemeinsam. Erlaubt ist eine 1:n-Beziehung,
in der Praxis ist sie heute aber immer 1:1. Warum es trotzdem zwei Tabellen sind, steht hier und in
der Code-Dokumentation von `AppTokenSession` und `SessionEvidence`.

---
