-- I-13 (docs/invarianten.md): je Konto hoechstens eine aktive Instanz einer Singleton-Methode.
-- Mehrere Instanzen erlaubt nur ein Verfahren, dessen Deklaration onePerDevice setzt; die Zeile
-- traegt das selbst (allows_multiple_instances), so braucht ein neues Verfahren keine Migration.
--
-- Wie bei V22: eine berechnete Spalte statt eines partiellen Index, den H2 nicht kennt.
ALTER TABLE account.auth_method ADD COLUMN active_singleton_account_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN active AND NOT allows_multiple_instances THEN account_id END);
CREATE UNIQUE INDEX ux_auth_method_active_singleton ON account.auth_method (active_singleton_account_id, method);
