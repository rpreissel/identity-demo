-- Die verwahrte PIN (ADR-21) liegt verschluesselt unter dem Hauptschluessel, den die Journey beim
-- Einrichten hatte (ADR-55); key_id nennt ihn. Keine Datenuebernahme.
ALTER TABLE auth_kobil.enrollment DROP COLUMN pin;
ALTER TABLE auth_kobil.enrollment ADD COLUMN pin VARBINARY(${secret_width}) NOT NULL;
ALTER TABLE auth_kobil.enrollment ADD COLUMN key_id UUID NOT NULL;
