import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val robolectricSdk by configurations.creating
val robolectricCompileSdk by configurations.creating
android {
    namespace = "app.leafreader"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.leafreader"
        minSdk = 26
        targetSdk = 35
        versionCode = 108
        versionName = "1.0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { getByName("release") { isDebuggable = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    robolectricSdk("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricCompileSdk("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jsoup:jsoup:1.18.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk, robolectricCompileSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    // Let Gradle fetch the fixed SDK through its normal repositories and cache.
    // Robolectric's default downloader writes a lock file in the user's home.
    systemProperty("robolectric.offline", "true")
    maxHeapSize = "2g"
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

// Optional local compile input, never a Gradle property or checked-in default.
// Evaluate only inside the task action; do not serialize this value in configuration/build caches.
val bookHubDefaultSources = layout.buildDirectory.dir("generated/bookHubDefault/java")
val generateBookHubDefault by tasks.registering {
    outputs.dir(bookHubDefaultSources)
    doNotTrackState("Optional local service target is generated afresh and must never enter build caches")
    notCompatibleWithConfigurationCache("Reads optional local input only at execution")
    doLast {
        val raw = System.getenv("LEAFREADER_DEFAULT_BOOKHUB_ORIGIN").orEmpty().trim()
        val origin = if (raw.isEmpty()) "" else {
            val u = runCatching { URI(raw) }.getOrNull()
            require(u != null && raw.length <= 2048 && u.scheme.equals("https", true) &&
                !u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
                u.rawPath.orEmpty() in listOf("", "/") && u.port in -1..65535 && u.port != 0) {
                "Optional BookHub default must be an HTTPS origin without credentials, path, query or fragment"
            }
            URI("https", null, u.host.lowercase(), if (u.port == 443) -1 else u.port, null, null, null).toASCIIString()
        }
        val literal = origin.replace("\\", "\\\\").replace("\"", "\\\"")
        val file = bookHubDefaultSources.get().file("app/leafreader/data/CompiledBookHubDefault.java").asFile
        file.parentFile.mkdirs()
        file.writeText("package app.leafreader.data;\npublic final class CompiledBookHubDefault {\n" +
            "  private CompiledBookHubDefault() {}\n  public static final String ORIGIN = \"" + literal + "\";\n}\n")
    }
}
android.sourceSets.getByName("main").java.srcDir(bookHubDefaultSources)
tasks.named("preBuild") { dependsOn(generateBookHubDefault) }
