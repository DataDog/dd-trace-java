plugins {
  `java-gradle-plugin`
  `jvm-test-suite`
  alias(libs.plugins.spotless)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
  plugins {
    create("inline-of-duty") {
      id = "dd-trace-java.inline-of-duty"
      implementationClass = "datadog.buildlogic.inlineofduty.InlineOfDutyPlugin"
    }
  }
}

testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit5)
      dependencies {
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter)
        implementation(gradleTestKit())
      }
      targets.configureEach {
        testTask.configure {
          maxParallelForks = 1
          testLogging { events("failed", "skipped") }
        }
      }
    }
  }
}

spotless {
  java {
    target("src/**/*.java")
    removeUnusedImports()
    forbidWildcardImports()
    googleJavaFormat(libs.versions.google.java.format.get())
  }
  kotlinGradle {
    ktlint(libs.versions.ktlint.get()).editorConfigOverride(mapOf("indent_size" to "2"))
  }
}
