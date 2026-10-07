-- Bezeichnung und Referenz eines Verfahrens sowie die Einzelheiten einer Anmeldung liegen versiegelt
-- unter dem Hauptschluessel des Kontos (ADR-55). Eintraege einer Einladung (ohne Konto) bleiben
-- lesbar. Breite je Modus wie in V45. Keine Datenuebernahme.
ALTER TABLE account.auth_method ALTER COLUMN label SET DATA TYPE VARBINARY(${secret_width});
ALTER TABLE account.auth_method ALTER COLUMN reference SET DATA TYPE VARBINARY(${secret_width});
ALTER TABLE account.sign_in_log ALTER COLUMN details SET DATA TYPE VARBINARY(${details_width});
