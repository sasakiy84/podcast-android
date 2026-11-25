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
        // Maven Central rate-limited the large Robolectric artifacts on release runners.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/")
            }
            filter {
                includeModule("org.robolectric", "android-all-instrumented")
                includeModule("org.robolectric", "nativeruntime-dist-compat")
            }
        }
    }
}
rootProject.name = "PodcastAndroid"
include(":app")
