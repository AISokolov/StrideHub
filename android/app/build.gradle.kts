import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val syncLocal = Properties().apply {
    val file = rootProject.file("sync.local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun devSetting(name: String, fallback: String = "") =
    providers.environmentVariable(name).orNull ?: syncLocal.getProperty(name, fallback)
fun quoted(value: String): String = "\"" + value.map { char ->
    when (char) {
        '\\' -> "\\\\"
        '"' -> "\\\""
        else -> if (char.isISOControl()) "\\u%04x".format(char.code) else char.toString()
    }
}.joinToString("") + "\""

android {
    namespace = "com.stridehub.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.stridehub.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SYNC_BASE_URL", "\"\"")
        buildConfigField("String", "SYNC_TOKEN", "\"\"")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "SYNC_BASE_URL", quoted(devSetting("STRIDEHUB_SYNC_URL", "http://192.168.0.79:3001")))
            buildConfigField("String", "SYNC_TOKEN", quoted(devSetting("STRIDEHUB_SYNC_TOKEN")))
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
