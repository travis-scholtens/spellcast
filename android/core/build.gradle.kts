plugins {
  `java-library`
  id("org.jetbrains.kotlin.jvm")
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  api("com.badlogicgames.gdx:gdx:1.12.1")
  api("com.badlogicgames.gdx:gdx-freetype:1.12.1")
  testImplementation("junit:junit:4.13.2")
}
