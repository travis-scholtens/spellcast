import java.util.Properties
import org.gradle.api.artifacts.Configuration

plugins {
  id("com.android.application")
}

val natives: Configuration by configurations.creating

val localProps = Properties().apply {
  rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun signingProp(name: String): String? =
  System.getenv(name) ?: findProperty(name)?.toString() ?: localProps.getProperty(name)

android {
  namespace = "com.travisscholtens.spellcast"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.travisscholtens.spellcast"
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  flavorDimensions += "formFactor"

  productFlavors {
    create("mobile") {
      dimension = "formFactor"
      versionCode = 1001
    }
    create("automotive") {
      dimension = "formFactor"
      versionCode = 2001
    }
  }

  signingConfigs {
    signingProp("SPELLCAST_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }?.let { keystore ->
      create("release") {
        storeFile = keystore
        storePassword = signingProp("SPELLCAST_KEYSTORE_PASSWORD")
        keyAlias = signingProp("SPELLCAST_KEY_ALIAS")
        keyPassword = signingProp("SPELLCAST_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      signingConfig = signingConfigs.findByName("release")
      ndk {
        debugSymbolLevel = "FULL"
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = false
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

val copyAndroidNatives by tasks.registering {
  doFirst {
    listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").let { abis ->
      abis.forEach { abi -> file("src/main/jniLibs/$abi").mkdirs() }
      natives.files.forEach { jar ->
        abis.firstOrNull { jar.name.endsWith("natives-$it.jar") }?.let { abi ->
          copy {
            from(zipTree(jar))
            into("src/main/jniLibs/$abi")
            include("*.so")
          }
        }
      }
    }
  }
}

val generateWordsAsset by tasks.registering {
  doFirst {
    val targetFile = file("src/main/assets/words.txt")
    if (!targetFile.exists()) {
      (System.getenv("WORDS_FILE") ?: findProperty("WORDS_FILE")?.toString())
        ?.let(::file)
        ?.takeIf { it.exists() }
        ?.useLines { lines ->
          targetFile.parentFile.mkdirs()
          targetFile.writeText(
            lines.map { it.trim().lowercase() }
              .filter { it.length in 2..7 }
              .joinToString("\n", postfix = "\n")
          )
        }
    }
  }
}

tasks.matching { it.name.contains("merge") && it.name.contains("Assets") }.configureEach {
  dependsOn(generateWordsAsset)
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
  dependsOn(copyAndroidNatives)
}

dependencies {
  val gdxVersion = "1.12.1"
  implementation(project(":core"))
  implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
  implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
  implementation("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")

  natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
  natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
  natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
  natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")
  natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-armeabi-v7a")
  natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-arm64-v8a")
  natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-x86")
  natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-x86_64")

  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  testImplementation("junit:junit:4.13.2")
}
