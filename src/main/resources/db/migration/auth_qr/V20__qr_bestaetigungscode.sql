-- Code in Gegenrichtung statt Vergleichscode (docs/07-betrieb.md #5): Ein Vergleichscode laesst sich
-- einem Opfer mitsamt Pairing-Link schicken. Den Bestaetigungscode zeigt erst die freigebende App,
-- und in den Browser des Angreifers kann das Opfer nicht tippen. Gespeichert wird nur sein Hash;
-- confirmation_attempts begrenzt das Raten im Browser.
ALTER TABLE auth_qr.login_request DROP COLUMN verification_code;
ALTER TABLE auth_qr.login_request ADD COLUMN confirmation_code_hash VARCHAR(64);
ALTER TABLE auth_qr.login_request ADD COLUMN confirmation_attempts INT DEFAULT 0 NOT NULL;
