plugins {
  `kotlin-dsl`
}

// Shadow 9's plugin API targets Java 17, so keep it isolated from Java 8 build-logic modules.
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
  implementation("com.gradleup.shadow:shadow-gradle-plugin:${libs.versions.shadow.get()}")
}
