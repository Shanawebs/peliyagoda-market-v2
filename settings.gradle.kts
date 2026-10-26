pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PeliyagodaMarketV2"
include(":app")
// Fully separate Android test APK. Existing app configuration is untouched.
include(":stagingOwner")

// Same full Market Pro source tree; separate Staging app ID, permissions and URL.
include(":stagingMarket")
