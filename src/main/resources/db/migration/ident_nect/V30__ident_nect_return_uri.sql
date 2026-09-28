-- ident_nect: die Rücksprungadresse gehört zum Fall, nicht mehr zum Modul.
-- Der Web-Kanal nennt sie beim Start (Keycloaks Action-URL des laufenden Schritts); ein
-- retry legt den neuen Fall mit derselben Adresse an. NULL heißt: der App-Kanal, `/app/`.
ALTER TABLE ident_nect.ident_tool_session ADD COLUMN return_uri VARCHAR(2048);
