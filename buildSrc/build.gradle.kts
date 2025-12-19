plugins {
  groovy
  `java-gradle-plugin`
  `kotlin-dsl`
  `jvm-test-suite`
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(8)
  }
}

gradlePlugin {
  plugins {
    create("instrument-plugin") {
      id = "dd-trace-java.instrument"
      implementationClass = "InstrumentPlugin"
    }

    create("muzzle-plugin") {
      id = "dd-trace-java.muzzle"
      implementationClass = "datadog.gradle.plugin.muzzle.MuzzlePlugin"
    }
    create("call-site-instrumentation-plugin") {
      id = "dd-trace-java.call-site-instrumentation"
      implementationClass = "datadog.gradle.plugin.csi.CallSiteInstrumentationPlugin"
    }

    create("tracer-version-plugin") {
      id = "dd-trace-java.tracer-version"
      implementationClass = "datadog.gradle.plugin.version.TracerVersionPlugin"
    }

    create("dump-hanged-test-plugin") {
      id = "dd-trace-java.dump-hanged-test"
      implementationClass = "datadog.gradle.plugin.dump.DumpHangedTestPlugin"
    }

    create("supported-config-generation") {
      id = "dd-trace-java.supported-config-generator"
      implementationClass = "datadog.gradle.plugin.config.SupportedConfigPlugin"
    }

    create("supported-config-linter") {
      id = "dd-trace-java.config-inversion-linter"
      implementationClass = "datadog.gradle.plugin.config.ConfigInversionLinter"
    }

    create("instrumentation-naming") {
      id = "dd-trace-java.instrumentation-naming"
      implementationClass = "datadog.gradle.plugin.naming.InstrumentationNamingPlugin"
    }
  }
}

apply {
  from("$rootDir/../gradle/repositories.gradle")
}

repositories {
  gradlePluginPortal()
}

dependencies {
  implementation(gradleApi())
  implementation(localGroovy())

  implementation("net.bytebuddy", "byte-buddy-gradle-plugin", "1.18.1")

  implementation("org.eclipse.aether", "aether-connector-basic", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-http", "1.1.0")
  implementation("org.apache.maven", "maven-aether-provider", "3.3.9")

  implementation("com.github.zafarkhaja:java-semver:0.10.2")
  implementation("com.github.javaparser", "javaparser-symbol-solver-core", "3.24.4")

  implementation("com.google.guava", "guava", "20.0")
  implementation(libs.asm)
  implementation(libs.asm.tree)

  implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.2"))
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.core:jackson-annotations")
  implementation("com.fasterxml.jackson.core:jackson-core")

  compileOnly(libs.develocity)
}

tasks.compileKotlin {
  dependsOn(":call-site-instrumentation-plugin:build")
}

testing {
  @Suppress("UnstableApiUsage")
  suites {
    val test by getting(JvmTestSuite::class) {
      targets.configureEach {
        testTask.configure {
          enabled = project.hasProperty("runBuildSrcTests")
        }
      }
    }

    withType(JvmTestSuite::class).configureEach {
      useJUnitJupiter(libs.versions.junit5)
      targets.configureEach {
        testTask
      }
    }
  }
}
