// ===================== V2__user_federation =====================

// Keycloak liest die Konten, statt sie zu spiegeln (ADR-38). Die orchestrator-Komponente aus V1 wird
// zur Nutzer-Federation ohne Import, mit fester Id (USER_STORAGE_COMPONENT_ID): aus ihr bildet
// Keycloak die Id jedes föderierten Nutzers und damit das sub. Cache höchstens 60 s, dann sind
// geänderte Stammdaten sichtbar. Das Entfernen der alten Komponente löscht die an ihr hängenden
// Nutzer; das ist gewollt. Der Peer-Auth-Signaturschlüssel wird nicht kopiert: Die Admin-API liefert
// ihn nur maskiert, die neue Komponente erzeugt beim Anlegen einen eigenen.

step("orchestrator-komponente als feste federation") {
    up {
        val old = components().query().first {
            it.providerId == "orchestrator" && it.providerType == "org.keycloak.storage.UserStorageProvider"
        }
        components().removeComponent(old.id)
        components().add(ComponentRepresentation().apply {
            id = USER_STORAGE_COMPONENT_ID
            name = old.name
            providerId = old.providerId
            providerType = old.providerType
            config = MultivaluedHashMap<String, String>(old.config).apply {
                put("cachePolicy", listOf("MAX_LIFESPAN"))
                put("maxLifespan", listOf("60000"))
                remove("peerAuthSigningKeyJwk")
            }
        }).close()
    }
    down {
        val current = components().component(USER_STORAGE_COMPONENT_ID).toRepresentation()
        components().removeComponent(USER_STORAGE_COMPONENT_ID)
        components().add(ComponentRepresentation().apply {
            name = current.name
            providerId = current.providerId
            providerType = current.providerType
            config = MultivaluedHashMap<String, String>(current.config).apply {
                remove("cachePolicy")
                remove("maxLifespan")
                remove("peerAuthSigningKeyJwk")
            }
        }).close()
    }
}
