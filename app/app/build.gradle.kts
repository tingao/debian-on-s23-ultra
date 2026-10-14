import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, read from a file that is deliberately not in the repository.
//
// keystore.properties sits at the Gradle root and is gitignored. It names the keystore, the
// alias and the passwords, so it never travels with the source. When it is missing, which is
// what a fresh clone looks like, the release build comes out UNSIGNED rather than quietly
// falling back to the debug key. That fallback is what produced the first two releases: a
// root-granting app signed with the public Android debug key, and Android only warns about a
// debuggable build rather than refusing it.
//
// To make your own: keytool -genkeypair -keystore my.jks -alias mykey -keyalg RSA -keysize 4096
// -validity 10000, then write the four properties below.
val keystoreProps = rootProject.file("keystore.properties")
val hasReleaseKey = keystoreProps.exists()
val releaseKey = Properties().apply {
    if (hasReleaseKey) keystoreProps.inputStream().use { load(it) }
}

android {
    namespace = "com.dsh.autoroot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.autoroot"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/*", "META-INF/**")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
