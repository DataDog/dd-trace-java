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

dependencies {
  implementation(libs.gradle.tooling.api)
  runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

gradlePlugin {
  plugins {
    create("smoke-test-app") {
      id = "dd-trace-java.smoke-test-app"
      implementationClass = "datadog.buildlogic.smoketest.SmokeTestAppPlugin"
    }
  }
}

@Suppress("UnstableApiUsage")
testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit5)
      dependencies {
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter)
        implementation(libs.junit.jupiter.params)
        implementation(libs.junit.jupiter.engine)
        implementation(libs.assertj.core)
        implementation(gradleTestKit())
      }
      targets.configureEach {
        testTask.configure {
          // Nested Gradle 8.x builds use the test JVM and do not support Java 25.
          javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
          })
          // The gradle-test-kit runner shells out to a Gradle daemon, which can be slow on a
          // cold cache. Surface stdout/stderr to make CI failures debuggable.
          testLogging {
            showStandardStreams = true
            events("failed", "skipped")
          }
        }
      }
    }
  }
}
