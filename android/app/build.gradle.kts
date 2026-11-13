plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.riowang88.nonfc"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.riowang88.nonfc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/dropbear"))
    // Untracked, and empty in a clean checkout: pictures a local build may carry but the
    // repository must not, such as PetKit's own pairing illustration (t6_bind_start.png).
    sourceSets["main"].assets.srcDir("local-assets")

    testOptions { unitTests.isReturnDefaultValues = true }
}

// The add-on's static build is the single copy of dropbear; the APK takes it at
// build time rather than committing a second one.
val copyDropbear by tasks.registering(Copy::class) {
    from("../../addon/petkit_local/web/static/bin") {
        include("dropbear-mipsel", "LICENSE.dropbear")
    }
    into(layout.buildDirectory.dir("generated/dropbear"))
}
tasks.named("preBuild") { dependsOn(copyDropbear) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.github.mwiede:jsch:0.2.21")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
