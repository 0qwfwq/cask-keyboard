plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.cask"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.cask"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Used by the native keyboard (ContextCompat / resource helpers / drawable.toBitmap).
    implementation("androidx.core:core-ktx:1.13.1")
    // Extracts the dominant/brand colour from the foreground app's icon for adaptive theming.
    implementation("androidx.palette:palette-ktx:1.0.0")
    // On-device neural rescorer for the autocorrect engine. The statistical engine runs without it;
    // this only "activates" once a trained model is dropped into assets/model/ (see tools/train_lm.py).
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // Emoji / GIF / emoticon picker: scrolling grids + animated-GIF image loading (GIPHY).
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    // On-device neural translation (the keyboard's translate mode). Same offline NMT models as
    // Google Translate's offline mode — accurate, natural, no API key and no network after the
    // per-language model downloads once, which keeps typing on-device like the rest of the keyboard.
    implementation("com.google.mlkit:translate:17.0.3")
}
