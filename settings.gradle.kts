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
        // Maven Central rate-limited the large Android SDK used by Robolectric on release runners.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/")
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
    }
}
rootProject.name = "PodcastAndroid"
include(":app")
