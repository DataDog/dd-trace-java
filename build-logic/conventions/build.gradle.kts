plugins {
  `kotlin-dsl`
  `jvm-test-suite`
}

testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit5)
      dependencies {
        implementation(gradleTestKit())
      }
    }
  }
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
