pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "identity-demo"

include("keycloak-extension")
include("keycloak-migrations")