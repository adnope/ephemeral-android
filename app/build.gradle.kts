import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val versionPropertiesFile = rootProject.file("version.properties")
check(versionPropertiesFile.exists()) { "Missing version.properties. Set VERSION_NAME before building." }
val versionProperties = Properties()
versionProperties.load(FileInputStream(versionPropertiesFile))

val appVersionName = versionProperties.getProperty("VERSION_NAME")?.trim()
    ?: error("VERSION_NAME is required in version.properties.")
check(appVersionName.isNotEmpty()) { "VERSION_NAME is required in version.properties." }

fun androidVersionCode(versionName: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""").matchEntire(versionName)
    check(match != null) { "VERSION_NAME must use semantic versioning, for example 0.3.0." }
    val major = match.groupValues[1].toInt()
    val minor = match.groupValues[2].toInt()
    val patch = match.groupValues[3].toInt()
    check(minor < 100 && patch < 100) { "VERSION_NAME minor and patch must be below 100." }
    return major * 1_000_000 + minor * 10_000 + patch * 100
}

android {
    namespace = "com.ephemeral.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ephemeral.android"
        minSdk = 26
        targetSdk = 36
        versionCode = androidVersionCode(appVersionName)
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    val media3Version = "1.10.1"

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    annotationProcessor("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
}
