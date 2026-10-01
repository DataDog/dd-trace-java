plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  `jvm-test-suite`
  alias(libs.plugins.spotless)
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
  // Keep aligned with buildSrc, whose Jackson classes are visible to applied plugins.
  implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.2"))
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
}

gradlePlugin {
  plugins {
    create("tag-registry-generator") {
      id = "dd-trace-java.tag-registry-generator"
      implementationClass = "datadog.buildlogic.tagRegistry.TagRegistryGeneratorPlugin"
    }
  }
}

testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit5)
      dependencies {
        implementation(platform("org.junit:junit-bom:${libs.versions.junit5.get()}"))
        implementation(libs.assertj.core)
        implementation(libs.tabletest)
        implementation(gradleTestKit())
      }
    }
  }
}

spotless {
  kotlin {
    target("src/**/*.kt")
    ktlint(libs.versions.ktlint.get()).editorConfigOverride(
      mapOf(
        "indent_size" to "2",
        "ktlint_standard_trailing-comma-on-call-site" to "disabled",
        "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
      ),
    )
  }
  kotlinGradle {
    ktlint(libs.versions.ktlint.get()).editorConfigOverride(mapOf("indent_size" to "2"))
  }
}
