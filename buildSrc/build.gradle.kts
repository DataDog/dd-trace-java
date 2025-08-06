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
  plugins {
    create("instrument-plugin") {
      id = "instrument"
      implementationClass = "InstrumentPlugin"
    }
    create("muzzle-plugin") {
      id = "muzzle"
      implementationClass = "MuzzlePlugin"
    }
    create("call-site-instrumentation-plugin") {
      id = "call-site-instrumentation"
      implementationClass = "datadog.gradle.plugin.CallSiteInstrumentationPlugin"
    }
    create("tracer-version-plugin") {
      id = "tracer-version"
      implementationClass = "datadog.gradle.plugin.version.TracerVersionPlugin"
    }
  }
}

apply {
  from("$rootDir/../gradle/repositories.gradle")
}

dependencies {
  implementation(gradleApi())
  implementation(localGroovy())

  implementation("net.bytebuddy", "byte-buddy-gradle-plugin", "1.17.5")

  implementation("org.eclipse.aether", "aether-connector-basic", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-http", "1.1.0")
  implementation("org.apache.maven", "maven-aether-provider", "3.3.9")

  implementation("com.github.zafarkhaja:java-semver:0.10.2")

  implementation("com.google.guava", "guava", "20.0")
  implementation("org.ow2.asm", "asm", "9.8")
  implementation("org.ow2.asm", "asm-tree", "9.8")
}

tasks.compileKotlin {
  dependsOn(":call-site-instrumentation-plugin:build")
}

testing {
  @Suppress("UnstableApiUsage")
  suites {
    val test by getting(JvmTestSuite::class) {
      dependencies {
        implementation(libs.spock.core)
        implementation(libs.groovy)
      }
      targets.configureEach {
        testTask.configure {
          enabled = project.hasProperty("runBuildSrcTests")
        }
      }
    }

    val integTest by registering(JvmTestSuite::class) {
      dependencies {
        implementation(gradleTestKit())
        implementation("org.assertj:assertj-core:3.25.3")
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
