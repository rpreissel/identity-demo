-- Bezeichnung und Referenz eines Verfahrens sowie die Einzelheiten einer Anmeldung liegen versiegelt
-- unter dem Hauptschluessel des Kontos (ADR-55), mit lesbarem Kopf. Eintraege einer Einladung
-- (ohne Konto) tragen den Kopf mit key=none. Keine Datenuebernahme.
ALTER TABLE account.auth_method ALTER COLUMN label SET DATA TYPE VARBINARY(512);
ALTER TABLE account.auth_method ALTER COLUMN reference SET DATA TYPE VARBINARY(512);
ALTER TABLE account.sign_in_log ALTER COLUMN details SET DATA TYPE VARBINARY(2048);
