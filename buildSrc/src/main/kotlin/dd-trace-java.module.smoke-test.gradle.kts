import datadog.gradle.plugin.smoketest.SmokeAgentArguments
import datadog.gradle.plugin.smoketest.SmokeTestArtifacts.SCOPE_DIAGNOSTICS_AGENT
import org.gradle.api.Named
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

plugins {
  id("dd-trace-java.conventions.java")
  id("dd-trace-java.conventions.testing.groovy")
}

tasks.matching { it.name.startsWith("forbiddenApis") }.configureEach {
  enabled = false
}
tasks.withType<Javadoc>().configureEach {
  enabled = false
}

// These agents are part of every smoke test's execution contract in this repository.
val agentJar = smokeTestAgentFrom(
  "smokeTestTracer",
  ":dd-java-agent",
  Bundling.BUNDLING_ATTRIBUTE,
  Bundling.SHADOWED
)
val diagnosticsAgentJar = smokeTestAgentFrom(
  "smokeTestDiagnostics",
  ":dd-java-agent:instrumentation-testing",
  LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
  SCOPE_DIAGNOSTICS_AGENT
)
val baseVersion = version.toString()
val testBuildDirectory = layout.buildDirectory
val diagnosticsQuiescenceMillis = providers.systemProperty("datadog.smoketest.scopeDiagnostics.quiescenceMillis").orElse("2000")

tasks.withType<Test>().configureEach {
  jvmArgumentProviders.add(
    objects.newInstance<SmokeAgentArguments>().apply {
      tracerJar.set(agentJar)
      tracerVersion.set(baseVersion)
      diagnosticsJar.set(diagnosticsAgentJar)
      buildDirectory.set(testBuildDirectory)
      quiescenceMillis.set(diagnosticsQuiescenceMillis)
    }
  )
}

fun <T : Named> smokeTestAgentFrom(
  name: String,
  producerPath: String,
  lookupAttribute: Attribute<T>,
  lookupValue: String
): Provider<RegularFile> {
  val agentDependencies = configurations.dependencyScope(name) {
    dependencies.add(project.dependencies.project(producerPath))
  }
  val agentClasspath = configurations.resolvable("${name}Classpath") {
    extendsFrom(agentDependencies)
    isTransitive = false
    attributes.attribute(lookupAttribute, objects.named(lookupAttribute.type, lookupValue))
  }
  return layout.file(agentClasspath.flatMap { it.elements }.map { it.single().asFile })
}
