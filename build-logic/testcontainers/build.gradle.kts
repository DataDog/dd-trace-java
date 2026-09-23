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
  implementation("com.google.cloud.tools:jib-core:0.28.2")
}

gradlePlugin {
  plugins {
    create("testcontainers") {
      id = "dd-trace-java.testcontainers"
      implementationClass = "datadog.buildlogic.testcontainers.TestcontainersPlugin"
    }
  }
}

testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit5)
      dependencies {
        implementation(libs.assertj.core)
        implementation(gradleTestKit())
      }
    }
  }
}
