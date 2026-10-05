# ADR-6: `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS

**Status:** umgesetzt.

**Kontext**: Der Orchestrator, also der Server dieses Projekts, steuert den ganzen Ablauf einer
Registrierung oder Anmeldung. Der Client (App oder Website) entscheidet selbst nichts. Er muss aber
nach jeder Antwort wissen, welchen Bildschirm er als nächstes zeigt und welchen Endpunkt er dann
aufruft. Die Frage ist, wie der Server dem Client den nächsten Schritt mitteilt.

**Entscheidung**: Jede Antwort der API enthält ein Objekt `next`. Es nennt nur die Adresse des
nächsten Schritts: den Typ, die `toolId` oder den `context` und den Schritt. Inhalte oder Links
liefert es nie mit. Der Client findet den nächsten Schritt über eine **eigene, feste
Routing-Tabelle im Client**. Sie ordnet jedem Paar `(toolId|context, step)` eine Komponente der
Oberfläche bzw. einen Endpunkt zu. Mehr dazu in [API](../05-api.md) Abschnitt 1 und
[Frontend](../10-frontend.md).

**Erwogene Alternative**: HATEOAS. Dabei liefert die Antwort fertige, anklickbare Links (`href`) mit.

**Warum diese**: Es gibt nur wenige mögliche nächste Schritte, und sie ändern sich selten. Das
Frontend braucht für jeden neuen Schritt ohnehin eine eigene Komponente; ein Link allein reicht nie.
Eine feste Tabelle zeigt außerdem, welche Übergänge das Frontend überhaupt kennt.

**Kosten**: Backend und Frontend müssen zueinander passen. Schickt das Backend einen neuen Wert für
`next`, zu dem es in der Routing-Tabelle des Frontends keinen Eintrag gibt, ist der Client in einem
Zustand, den er nicht behandelt.

---
