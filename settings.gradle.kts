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

rootProject.name = "SRCS"
include(":core")
// CI's iOS job builds only the shared core (set SRCS_CORE_ONLY=1); the Android app needs the Android SDK.
if (System.getenv("SRCS_CORE_ONLY") == null) include(":app")
