import org.gradle.internal.classpath.Instrumented.systemProperty

plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  `jvm-test-suite`
  alias(libs.plugins.shadow)
}

val jib = configurations.create("jib")
val conflictingBuildSrc = configurations.create("conflictingBuildSrc")
configurations.compileOnly { extendsFrom(jib) }

dependencies {
  jib("com.google.cloud.tools:jib-core:0.28.2")
  conflictingBuildSrc("org.apache.httpcomponents:httpclient:4.3.5")
}

// buildSrc exports an older HttpClient through the parent classloader. Keep Jib's
// dependencies private, including when this plugin is consumed as an included build.
tasks.shadowJar {
  configurations = listOf(jib)
  enableAutoRelocation = true
  relocationPrefix = "datadog.buildlogic.testcontainers.internal"
  mergeServiceFiles()
}
configurations.apiElements {
  outgoing.artifacts.clear()
  outgoing.artifact(tasks.shadowJar)
}
configurations.runtimeElements {
  outgoing.artifacts.clear()
  outgoing.artifact(tasks.shadowJar)
}
tasks.pluginUnderTestMetadata {
  pluginClasspath.setFrom(tasks.shadowJar)
}
tasks.test {
  inputs.files(conflictingBuildSrc)
  systemProperty("test.buildSrc.classpath", conflictingBuildSrc.asPath)
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
        implementation(libs.okhttp3.mockwebserver)
        implementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp3.testing.get()}")
        implementation(gradleTestKit())
      }
    }
  }
}
