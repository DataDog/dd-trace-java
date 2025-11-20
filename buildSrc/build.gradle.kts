plugins {
  groovy
  `java-gradle-plugin`
  `kotlin-dsl`
  `jvm-test-suite`
  id("com.diffplug.spotless") version "6.13.0"
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(8)
  }
}

gradlePlugin {
  // Sorted list of plugins:
  plugins {
    create("call-site-instrumentation-plugin") {
      id = "call-site-instrumentation"
      implementationClass = "datadog.gradle.plugin.CallSiteInstrumentationPlugin"
    }

    create("dump-hanged-test-plugin") {
      id = "datadog.dump-hanged-test"
      implementationClass = "datadog.gradle.plugin.dump.DumpHangedTestPlugin"
    }

    create("groovy-spock-plugin") {
      id = "datadog.groovy-spock"
      implementationClass = "datadog.gradle.plugin.config.groovy.GroovySpockConventionPlugin"
    }

    create("instrument-plugin") {
      id = "instrument"
      implementationClass = "InstrumentPlugin"
    }

    create("muzzle-plugin") {
      id = "muzzle"
      implementationClass = "datadog.gradle.plugin.muzzle.MuzzlePlugin"
    }

    create("supported-config-generation") {
      id = "datadog.supported-config-generator"
      implementationClass = "datadog.gradle.plugin.config.SupportedConfigPlugin"
    }

    create("supported-config-linter") {
      id = "datadog.config-inversion-linter"
      implementationClass = "datadog.gradle.plugin.config.ConfigInversionLinter"
    }

    create("tracer-version-plugin") {
      id = "datadog.tracer-version"
      implementationClass = "datadog.gradle.plugin.version.TracerVersionPlugin"
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

  // We have to use Spock with Groovy3 as Gradle 8.x bundled with Groovy3.
  // TODO: We can refactor `buildSrc` folder to not use Groovy at all.
  testImplementation(libs.spock.core.groovy3)
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

    val integTest by registering(JvmTestSuite::class) {
      dependencies {
        implementation(gradleTestKit())
      }
      // Makes the gradle plugin publish its declared plugins to this source set
      gradlePlugin.testSourceSet(sources)
    }

    withType(JvmTestSuite::class).configureEach {
      useJUnitJupiter(libs.versions.junit5)
      targets.configureEach {
        testTask
      }
    }
  }
}
