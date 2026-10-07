# ADR-21: KOBIL-Anbindung — PIN im Backend, Nachweis über eine Einmalkennung

**Status:** umgesetzt.

**Kontext**: **KOBIL** ist ein externer Anbieter. Seine App dient als Anmeldeverfahren mit
**Gerätebindung**: Beim Einrichten entsteht auf dem Smartphone ein Schlüssel, der das Gerät nie
verlässt. Wer sich später anmeldet, beweist damit, dass er dieses Gerät besitzt. In diesem Projekt
ist KOBIL simuliert (siehe [Glossar](../glossar/glossar.md)). Normalerweise vergibt der Nutzer bei
KOBIL eine eigene PIN und tippt sie bei jeder Anmeldung ein. Das wäre ein weiteres Geheimnis, das er
sich merken muss.

Das Verfahren `kobil` zeigt, wie man einen solchen Dienstleister für die Bindung an ein Gerät
einbindet, ohne dem Nutzer ein weiteres Geheimnis abzuverlangen. Dafür gelten zwei Entscheidungen.

## 1. Der PIN liegt im Backend, und das Entsperren zählt trotzdem

**Entscheidung.** Der Nutzer vergibt die KOBIL-PIN nicht und tippt sie auch nicht ein. Stattdessen
erzeugt das Backend des Tools die PIN und verwahrt sie. Für jede Anmeldung gibt es sie frei, nachdem
sich der Client auf dem Gerät entsperrt hat. Dafür gibt es zwei Wege:

- mit einem Gerätegeheimnis, das durch Biometrie geschützt ist (etwa Fingerabdruck),
- mit dem Passwort des Kontos.

Wie entsperrt wurde, ist das `userVerification` des Verfahrens (`biometric` bzw. `pin`). Es ist kein
zweiter Nachweis. Beide Wege erreichen das Sicherheitsniveau `loa2`, genau wie `auth-device`.

**Welche Wege es gibt, rechnet der Server aus.** Den Weg über Biometrie gibt es nur, wenn der Nutzer
zugestimmt hat; dann gibt es einen `unlock_secret_hash`. Den Weg über das Passwort gibt es nur,
solange das Konto ein Passwort hat. `auth-kobil` nennt im `stepData` die Wege, die es tatsächlich
gibt (`unlockOptions`). Ein Weg, den es nicht gibt, könnte nur zu einem Fehlversuch führen. Dieser
Fehlversuch würde beim Zähler für fehlgeschlagene Anmeldungen mitgezählt.

Dafür verrät die Antwort, ob das Konto ein Passwort hat. Das ist vertretbar: Der Schritt läuft nur für
einen Aufrufer, dessen Schlüssel schon zu einem Credential dieses Kontos passt. Und derselbe Aufrufer
sieht direkt danach ohnehin `activeMethods`, die Liste der aktiven Verfahren.

Im Code steht das als Typ, nicht als Kommentar: `KobilUnlockCredential` ist ein `sealed interface`
(Gerätegeheimnis **oder** Passwort) und kennt seinen Faktortyp selbst. Das Passwort des Kontos wird
als `pin` gemeldet, nie als `password`. Der Grund: Ein `amr`-Eintrag `password` würde über
`findActiveMethod(accountId, "password")` den Datensatz des echten Passwortverfahrens diesem Durchlauf
zuordnen. Dann würde das Passwort doppelt gezählt.

Warum die PIN dafür für den Server lesbar verwahrt wird, nicht als Hash, begründet
ADR-22; seit ADR-55 liegt sie versiegelt. Die Begründung gibt
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

**Erwogene Alternativen.**

- Den üblichen Weg von KOBIL beibehalten: Der Nutzer vergibt eine PIN und tippt sie ein. Verworfen,
  weil das Ziel gerade war, kein weiteres Geheimnis zu verlangen.
- Nur `{possession}` (Besitz) melden, weil der Server nicht sehen kann, wie entsperrt wurde. Das
  entspräche der Regel „nur Faktoren melden, die dem Server nachweisbar sind“ (siehe
  [Orchestrierung](../04-orchestrierung.md), Abschnitt 4). Das ist strenger und in einem Punkt
  richtiger. Es hätte den Weg über Biometrie aber auf `loa1` gesetzt. Dieselbe Handlung wäre dann in
  zwei Verfahren unterschiedlich viel wert. Und `auth-device` meldet `inherence` (etwas, das man ist)
  von Anfang an aus derselben Angabe des Clients. Es würde damit zu einer Ausnahme, die niemand
  erklärt.

**Folge.** Die Ausnahme von „nur nachweisbare Faktoren“ gilt für zwei Verfahren, `auth-device` und
`auth-kobil`. In der Orchestrierung steht sie als eine benannte Ausnahme.

## 2. Der Client trägt eine Einmalkennung, nicht die Assertion

**Entscheidung.** Bei `auth-kobil` wird der Nachweis nicht über den Client übermittelt. Die App
erhält vom KOBIL-SDK nur ein Einmalpasswort. Das Backend tauscht dieses Einmalpasswort selbst beim
Anbieter gegen die Assertion ein. Die **Assertion** ist die signierte Aussage über das Gerät, samt
Gerätekennung und Risikosignalen. Der Besitzfaktor ist damit stärker belegt als bei jedem anderen
Verfahren. Denn er beruht auf einer Assertion, die unser Backend selbst abholt, und nicht auf einer
Signatur des Clients.

**Erwogene Alternative.** Die signierte Assertion über den Client weiterreichen und im Server prüfen,
nach dem Muster von `device-proof+jwt` bei `auth-device`. Dafür bräuchte man aber einen
vertrauenswürdigen Prüfschlüssel und ein Signaturformat. Beides nennt die öffentliche Dokumentation
von KOBIL nicht. Ein selbst erfundenes Format würde sich als das echte ausgeben.

**Folgen.**

- Bei der Anmeldung ist unser Server auf den Server des Anbieters angewiesen. Ist dieser nicht
  erreichbar, lässt sich das Verfahren nicht nutzen.
- Ein manipulierter Client kann nichts behaupten. Er kann eine Kennung nur zurückhalten oder
  wiederholen. Beides endet in derselben Antwort („Bestätigung nicht erkannt“), weil sich eine
  Assertion genau einmal einlösen lässt.
- Die Gerätekennung **fragt der Server bei KOBIL ab**. Er übernimmt sie nie vom Client, denn mit ihr
  wird jede spätere Anmeldung verglichen.
- Lehnt KOBIL wegen eines Risikos ab, gibt es dafür einen **eigenen** Fehlergrund („Gerät als
  unsicher gemeldet“). Denn diese Ablehnung ist eine Aussage über das Gerät und kein Versehen des
  Nutzers. Bewusst in Kauf genommen: Beim Zähler für fehlgeschlagene Anmeldungen zählt sie wie ein
  falsches Passwort. Ein manipuliertes (gerootetes) Telefon kann seinen Besitzer also aussperren.

## Geschichte

Die zweite Entscheidung stand ursprünglich als eigene ADR-23. Beide beschreiben dieselbe Anbindung
und sind deshalb hier zusammengeführt.
