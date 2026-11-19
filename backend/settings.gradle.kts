pluginManagement {
    // The plugin portal mirrors Maven Central for plugin dependencies; listing Central too lets builds resolve them
    // when the portal's mirror has an outage.
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "backend"
