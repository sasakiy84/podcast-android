plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingVariables = listOf(
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
)
val hasReleaseSigning = signingVariables.all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "io.github.sasakiy84.podcast"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.sasakiy84.podcast"
        minSdk = 36
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("14").get().toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("0.7.4").get()
    }
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH"))
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "podcast-release"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }
    signingConfigs.getByName("debug") {
        System.getenv("ANDROID_USER_HOME")?.let { storeFile = file("$it/debug.keystore") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = providers.gradleProperty("testHeap").orElse("512m").get()
            // Android 16's shared-memory shadow needs this JDK module during local tests.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            val testHome = layout.buildDirectory.dir("test-home").get().asFile
            it.systemProperty("user.home", testHome.absolutePath)
            it.systemProperty("podcast.screenshotDir", layout.buildDirectory.dir("reports/screenshots").get().asFile.absolutePath)
            it.doFirst { testHome.mkdirs() }
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            for (property in listOf(
                "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort",
                "http.nonProxyHosts", "javax.net.ssl.trustStore",
            )) {
                System.getProperty(property)?.let { value -> it.systemProperty(property, value) }
            }
        }
    }
    lint { warningsAsErrors = true }
}

// Release artifacts must never silently fall back to unsigned or debug signing.
tasks.configureEach {
    if (name == "validateSigningRelease" || name == "packageRelease" || name == "packageReleaseBundle") {
        doFirst {
            check(hasReleaseSigning) { "Release signing requires: ${signingVariables.joinToString()}" }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
