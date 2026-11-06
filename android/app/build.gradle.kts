import org.gradle.api.artifacts.Configuration

plugins {
  id("com.android.application")
}

val natives: Configuration by configurations.creating

android {
  namespace = "com.spellcast.automotive"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.spellcast.automotive"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
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
