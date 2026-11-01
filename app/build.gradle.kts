plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("verified-test-sdk"))
}
fun lockedClasspath(name: String): Boolean =
    name in setOf("debugCompileClasspath", "debugRuntimeClasspath", "releaseCompileClasspath", "releaseRuntimeClasspath", "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath", "releaseUnitTestCompileClasspath", "releaseUnitTestRuntimeClasspath", "robolectricSdk")
configurations.configureEach {
    if (lockedClasspath(name)) resolutionStrategy.activateDependencyLocking()
}
dependencyLocking { lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT) }
tasks.register("resolveLockedDependencies") {
    description = "Resolve the application/test classpaths and verified Robolectric SDK for lock updates."
    doLast { configurations.filter { it.isCanBeResolved && lockedClasspath(it.name) }.forEach {
        it.incoming.artifactView {
            componentFilter { id -> id is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        }.files.files
    } }
}

val unsignedRelease = providers.gradleProperty("unsignedRelease").map(String::toBoolean).orElse(false).get()
// Signing is external; reject credentials accidentally passed to the build environment.
check(listOf("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD")
    .none { !System.getenv(it).isNullOrBlank() }) {
    "Signing credentials must not be passed to Gradle. Use the isolated signing step."
}

android {
    namespace = "io.github.sasakiy84.podcast"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.sasakiy84.podcast"
        minSdk = 36
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("20").get().toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("0.9.3").get()
    }
    signingConfigs.getByName("debug") {
        System.getenv("ANDROID_USER_HOME")?.let { storeFile = file("$it/debug.keystore") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("third_party"))
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The screen catalog produces review artifacts; it does not assert image equality.
            if (!providers.gradleProperty("captureScreenshots").map(String::toBoolean).orElse(false).get()) {
                it.exclude("**/ScreenCapture.class")
            }
            it.maxHeapSize = providers.gradleProperty("testHeap").orElse("512m").get()
            // Android 16's shared-memory shadow needs this JDK module during local tests.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            val testHome = layout.buildDirectory.dir("test-home").get().asFile
            it.systemProperty("user.home", testHome.absolutePath)
            it.systemProperty("podcast.screenshotDir", layout.buildDirectory.dir("reports/screenshots").get().asFile.absolutePath)
            it.doFirst { testHome.mkdirs() }
            it.dependsOn(prepareRobolectricSdk)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("verified-test-sdk").get().asFile.absolutePath)
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

// Unsigned release output requires an explicit opt-in; signing happens in the isolated CI job.
tasks.configureEach {
    if (name == "packageRelease" || name == "packageReleaseBundle") {
        inputs.property("unsignedRelease", unsignedRelease)
        doFirst {
            check(unsignedRelease) { "Use explicit -PunsignedRelease=true, then sign outside Gradle." }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
