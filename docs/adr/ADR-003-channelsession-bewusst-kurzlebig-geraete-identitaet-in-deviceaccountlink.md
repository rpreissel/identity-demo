# ADR-3: `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink`

**Status:** umgesetzt.

**Kontext**: Ein **Kanal** (`ChannelSession`) ist eine Verbindung eines Nutzers zum Orchestrator,
also zum Server dieses Projekts. Die Verbindung läuft entweder über die App oder über die Website.
Die App weist jede Anfrage mit einem Schlüssel aus, der das Smartphone nie verlässt (DPoP). Am
Fingerabdruck dieses Schlüssels, dem `bindingKeyRef`, erkennt der Server das Gerät wieder. Die Frage
ist, wo sich der Server merkt, welches Smartphone zu welchem Konto gehört: im Kanal selbst oder an
einer eigenen Stelle. Die Begriffe erklärt auch das [Glossar](../glossar/glossar.md).

**Entscheidung**: Ein Kanal (`ChannelSession`) wird nur begrenzt aufbewahrt, heute 14 Tage (siehe
[Betrieb](../07-betrieb.md)). Er enthält keine langlebige Zuordnung zu einem Gerät. Die einzige
dauerhafte Zuordnung Gerät -> Account (`bindingKeyRef -> accountId`) liegt in einer eigenen Tabelle,
der Geräteverknüpfung (`DeviceAccountLink`). Mehr dazu in [Domänenmodell](../02-domaenenmodell.md)
Abschnitt 1 und [DPoP-Bindung](../09-dpop.md) Abschnitt 3.

**Erwogene Alternative**: Den Kanal (`ChannelSession`) selbst langlebig machen und das Gerät darüber
wiedererkennen. Ein wiederkehrendes Gerät würde dann dieselbe Sitzung fortsetzen.

**Warum diese**: Eine Sitzung, die ein Gerät über Wochen darstellt, vermischt zwei Lebensdauern in
einer Entität: die Nutzung eines Kanals (Stunden) und die Identität des Geräts (dauerhaft). Der
`bindingKeyRef` beweist nur, welches Gerät spricht. Er sagt nie, welche Sitzung fortzusetzen ist.
Kommt ein Gerät wieder, legt der Server deshalb **immer** einen neuen Kanal (`ChannelSession`) an und
trägt darin nur schon die `accountId` ein.

**Kosten**: Es gibt zwei Konzepte statt eines. Wer wissen will, zu welchem Konto ein Gerät gehört,
muss das ausdrücklich in der Geräteverknüpfung (`DeviceAccountLink`) nachschlagen.

---
