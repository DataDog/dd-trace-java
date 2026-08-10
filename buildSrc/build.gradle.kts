plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  `jvm-test-suite`
  id("com.diffplug.spotless") version "8.4.0"
}

// The buildSrc still needs to target Java 8 as build time instrumentation and muzzle plugin
// allow to schedule workers on different JDK version.
java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
  }
}

gradlePlugin {
  plugins {
    create("instrument-plugin") {
      id = "dd-trace-java.build-time-instrumentation"
      implementationClass = "datadog.gradle.plugin.instrument.BuildTimeInstrumentationPlugin"
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

    create("version-file-plugin") {
      id = "dd-trace-java.version-file"
      implementationClass = "datadog.gradle.plugin.version.WriteVersionFilePlugin"
    }

    create("dump-hanged-test-plugin") {
      id = "dd-trace-java.dump-hanged-test"
      implementationClass = "datadog.gradle.plugin.dump.DumpHangedTestPlugin"
    }

    create("test-jvm-constraints-plugin") {
      id = "dd-trace-java.test-jvm-constraints"
      implementationClass = "datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsPlugin"
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

    create("sca-enrichments-plugin") {
      id = "dd-trace-java.sca-enrichments"
      implementationClass = "datadog.gradle.plugin.sca.ScaEnrichmentsPlugin"
    }

    create("jardiff-plugin") {
      id = "dd-trace-java.jardiff"
      implementationClass = "datadog.gradle.plugin.jardiff.JardiffPlugin"
    }
  }
}

apply(from = "$rootDir/../gradle/repositories.gradle")

repositories {
  gradlePluginPortal()
}

dependencies {
  implementation(gradleApi())

  implementation("net.bytebuddy", "byte-buddy-gradle-plugin", "1.18.10")

  implementation("org.eclipse.aether", "aether-connector-basic", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-http", "1.1.0")
  implementation("org.eclipse.aether", "aether-transport-file", "1.1.0")
  implementation("org.apache.maven", "maven-aether-provider", "3.3.9")

  implementation("com.github.zafarkhaja:java-semver:0.10.2")
  implementation(libs.javaparser.symbol.solver)

  implementation(libs.asm)
  implementation(libs.asm.tree)

  implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.2"))
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.core:jackson-annotations")
  implementation("com.fasterxml.jackson.core:jackson-core")

  compileOnly(libs.develocity)

  testImplementation("me.champeau.jmh:jmh-gradle-plugin:0.7.3")
}

tasks.compileKotlin {
  dependsOn(":call-site-instrumentation-plugin:build")
  dependsOn(":modifiable-config-agent:build")
}

testing {
  @Suppress("UnstableApiUsage")
  suites {
    named<JvmTestSuite>("test") {
      dependencies {
        implementation(libs.assertj.core)
      }
      targets.configureEach {
        testTask.configure {
          enabled = providers.gradleProperty("runBuildSrcTests").isPresent or providers.systemProperty("idea.active").isPresent
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
