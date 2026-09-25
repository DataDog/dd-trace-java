plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  alias(libs.plugins.shadow)
}

val jib = configurations.register("jib") {
  dependencies.add(project.dependencies.create("com.google.cloud.tools:jib-core:0.28.2"))
}
configurations.compileOnly { extendsFrom(jib) }

// buildSrc exports an older HttpClient through the parent classloader. Keep Jib's
// dependencies private, including when this plugin is consumed as an included build.
tasks.shadowJar {
  configurations.add(jib)
  enableAutoRelocation = true
  relocationPrefix = "datadog.buildlogic.testcontainers.internal"
  mergeServiceFiles()
}
configurations.apiElements {
  outgoing.artifacts.clear()
  outgoing.variants.clear()
  outgoing.artifact(tasks.shadowJar)
}
configurations.runtimeElements {
  outgoing.artifacts.clear()
  outgoing.variants.clear()
  outgoing.artifact(tasks.shadowJar)
}
tasks.pluginUnderTestMetadata {
  pluginClasspath.setFrom(tasks.shadowJar)
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
      }
    }
  }
}
