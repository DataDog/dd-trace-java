plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
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
  implementation("org.gradle:gradle-tooling-api:8.14.5")
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
