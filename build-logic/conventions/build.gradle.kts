plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  `jvm-test-suite`
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
  }
}

@Suppress("UnstableApiUsage")
testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit5)
      dependencies {
        implementation(libs.junit.jupiter)
        implementation(libs.junit.jupiter.engine)
        implementation(libs.assertj.core)
        implementation(gradleTestKit())
      }
    }
  }
}
