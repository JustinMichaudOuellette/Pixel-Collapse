plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ca.justinmo.wfcbitmap"
  compileSdk {
    version = release(37)
  }

  // Pinned so the C solver is built by the same toolchain everywhere.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "ca.justinmo.wfcbitmap"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Phones are arm64 and the emulator is x86_64; every other ABI would just triple the build time.
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }

    externalNativeBuild {
      cmake {
        // The solver is plain C and uses no C++ runtime, so do not link one.
        arguments += listOf("-DANDROID_STL=none")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildTypes {
    debug {
      // The C solver at -O0 is slow enough to read like a bug, so debug keeps optimisation and only
      // adds full symbols; step-through still works with inlined-free code.
      externalNativeBuild {
        cmake {
          arguments += listOf("-DCMAKE_BUILD_TYPE=RelWithDebInfo")
        }
      }
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
      // Strip the shared object: the release APK ships optimised code, not symbol tables.
      ndk {
        debugSymbolLevel = "none"
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    // The tool catalog reads BuildConfig.DEBUG to keep the camera tool out of release builds.
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Toolbar glyphs (Save, Clear, ...). The extended set is large, but R8 removes the icons nothing
  // references, so only the handful used here reach the release APK.
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
  // Compose *testing* support: it was on the app's main classpath, which dragged the whole AR/XR
  // runtime (about 7 MB of native libraries) into the APK and made R8 fail on the XR extension
  // classes that are not part of the platform. The app uses none of it; tests still can.
  androidTestImplementation(libs.androidx.compose.testing)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
