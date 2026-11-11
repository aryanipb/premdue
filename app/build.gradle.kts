import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.aryan.premdue"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aryan.premdue"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }
    // signing/ is git-ignored: a fresh clone must still configure and build debug.
    val signingProps = rootProject.file("signing/signing.properties")
    signingConfigs {
        if (signingProps.exists()) create("release") {
            val props = Properties().apply { signingProps.inputStream().use { load(it) } }
            storeFile = rootProject.file("signing/premdue.p12")
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("storePassword")
        }
    }
    buildTypes { getByName("release") { if (signingProps.exists()) signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
