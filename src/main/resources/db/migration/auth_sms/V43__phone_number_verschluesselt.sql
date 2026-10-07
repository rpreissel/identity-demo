-- Die Mobilnummer liegt verschluesselt unter dem Hauptschluessel, den die Journey beim Einrichten
-- hatte (ADR-55); key_id nennt ihn. Keine Datenuebernahme: keine Produktivdaten, die Demo-Datenbank
-- wird neu aufgebaut.
ALTER TABLE auth_sms.enrollment DROP COLUMN phone_number;
ALTER TABLE auth_sms.enrollment ADD COLUMN phone_number VARBINARY(256) NOT NULL;
ALTER TABLE auth_sms.enrollment ADD COLUMN key_id UUID NOT NULL;
