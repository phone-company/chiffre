import java.util.Properties

plugins {
    id("com.android.application")
}

// Signierdaten kommen aus keystore.properties im Projektordner (steht in .gitignore).
// Fehlt die Datei, entsteht eine unsignierte APK. So baut auch F-Droid.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    // Paketname des Codes. Bleibt fest, auch wenn die applicationId geändert wird.
    namespace = "app.chiffre"
    compileSdk = 36

    defaultConfig {
        // Die applicationId lässt sich nach der ersten Veröffentlichung nicht mehr ändern.
        // Vorher bei Bedarf anpassen, zum Beispiel auf io.github.DEIN-NAME.chiffre
        applicationId = "app.chiffre.notes"
        minSdk = 26
        targetSdk = 36
        // Bei jeder neuen Version beide Werte erhöhen. Wörtliche Zahlen, damit F-Droid sie lesen kann.
        versionCode = 159
        versionName = "1.45.43"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keine von Google verschlüsselten Abhängigkeitsdaten in der APK (F-Droid-Vorgabe).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
