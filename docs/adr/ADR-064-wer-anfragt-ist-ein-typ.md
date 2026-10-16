# ADR-64: Wer anfragt, ist ein Typ (`CallerProof`), kein String mit Präfix

**Status:** entschieden und umgesetzt 2026-10-16 (Issue `DPoP-demo-v9gp`).

**Worum es geht.** Der Nachweis des Aufrufers war ein `String bindingKeyRef`:

- Bei der App ist das der Fingerabdruck des Geräteschlüssels.
- Beim Web ist es `"kc:" + channel_binding` aus Keycloaks Peer-Auth-Assertion.

Zwei Zugriffsprüfer erkannten den Fall am Präfix (`DeviceChannelAccessGuard`,
`KeycloakChannelAccessGuard`). `ChannelSession.channelBinding` hielt beim Web-Kanal immer die eigene
Kanal-Id, denn `KeycloakChannelService` lehnt jede andere Bindung ab; die Spalte trug also keine
Information. Für „nur die App“ gab es den Schalter `@BindingKey(dpopOnly = true)`.

**Entscheidung.**

- `CallerProof` im Tool-Vertrag hat zwei Varianten:
  - `DeviceKey(thumbprint)` für die App, belegt per DPoP.
  - `KeycloakFlow(channelSessionId)` für das Web, belegt per Peer-Auth.
- `@BindingKey`-Parameter und `ToolContext.caller` tragen diesen Typ. Ein Parameter vom Typ
  `CallerProof.DeviceKey` nimmt nur DPoP an; der Schalter `dpopOnly` entfällt.
- Ein einziger `ChannelAccessGuard` lässt einen App-Kanal nur für seinen Schlüssel und einen Web-Kanal
  nur für die Assertion mit seiner eigenen Id zu.
- Die Spalte `channel_session.channel_binding` entfällt (V55).
- Eine Assertion, deren `channel_binding` keinen Kanal nennt, passt zu keinem Kanal (403).

**Warum.** Eine Konvention im String wird zu einem Typ, den der Compiler prüft. Zwei Prüfer werden
zu einem. Eine Spalte ohne Information verschwindet. Eine Geräteangabe kann per Typ nicht mehr aus
einem Web-Nachweis stammen: Tools, die nur auf der App laufen, fragen `requireDeviceKey()`.

**Preis.** Viele Signaturen ändern sich (`ChannelService`, `ChannelController`, Tool-Vertrag).
Eine Schema-Migration ist nötig, eine Datenmigration nicht.
