plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.anoterm"
  compileSdk = 36
  defaultConfig {
    applicationId = "app.anoterm"
    minSdk = 24
    targetSdk = 36
    versionCode = 37
    versionName = "0.22.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      // 名前は `ANOTERM_` に揃えた。`WANOTERM_` も読むのは、既にそちらで設定して
      // いる手元や CI を、リポジトリ名を変えただけで壊さないため。
      // どちらも無ければ署名なしビルドになり、クローン直後でもビルドは通る。
      fun signingProperty(name: String): String? =
          (project.findProperty("ANOTERM_" + name) as String?)
              ?: (project.findProperty("WANOTERM_" + name) as String?)

      val keystorePath = signingProperty("STORE_FILE")
      if (keystorePath != null) {
        storeFile = file(keystorePath)
        storePassword = signingProperty("STORE_PASSWORD")
        keyAlias = signingProperty("KEY_ALIAS")
        keyPassword = signingProperty("KEY_PASSWORD")
        // v2 だけだと API 24 未満で検証できず、v3 が無いと将来の鍵ローテーションが
        // できない。minSdk 24 なので v1 は理屈上不要だが、サイドロード時に古い
        // 検証経路を通る環境があるため付けておく。
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // 名前は signingConfigs と必ず揃えること。片方だけ直すと、署名設定は出来ているのに
      // buildType が拾わず、**エラーも出さずに未署名の APK が出る**。一度やった。
      val hasReleaseKey =
          project.findProperty("ANOTERM_STORE_FILE") != null ||
              project.findProperty("WANOTERM_STORE_FILE") != null
      if (hasReleaseKey) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      isMinifyEnabled = false
      applicationIdSuffix = ".debug"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = true
  }

  packaging {
    resources {
      excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/NOTICE", "/META-INF/LICENSE")
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.appcompat)

  implementation(libs.sshj)
  implementation(libs.slf4j.android)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.bouncycastle.bcpkix)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
