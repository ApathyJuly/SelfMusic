plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val debugServerBaseUrl = providers.gradleProperty("selfmusic.serverBaseUrl.debug")
    .orElse("https://127.0.0.1:8080")
    .get()
val releaseServerBaseUrl = providers.gradleProperty("selfmusic.serverBaseUrl.release")
    .orElse("https://miwifi-ra80v2-srv.taild2defd.ts.net/SelfMUsicServer")
    .get()
fun String.asBuildConfigValue() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android { namespace = "com.selfmusic"; compileSdk = 35; buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.selfmusic"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
    }
    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SERVER_BASE_URL", debugServerBaseUrl.asBuildConfigValue())
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER_BASE_URL", releaseServerBaseUrl.asBuildConfigValue())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
