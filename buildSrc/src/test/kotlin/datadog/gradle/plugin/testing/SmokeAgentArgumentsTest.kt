package datadog.gradle.plugin.testing

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class SmokeAgentArgumentsTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    writeSettings(
      """
      rootProject.name = "smoke-agent-cache"
      buildCache { local { directory = file("cache") } }
      """,
    )
    writeFile(
      "build.gradle",
      """
      import datadog.gradle.plugin.testing.SmokeAgentArguments
      import datadog.gradle.plugin.version.AgentVersionFiles
      import java.util.jar.JarFile

      plugins { id 'dd-trace-java.version-file' }

      @CacheableTask
      abstract class AgentProbe extends DefaultTask {
        private final SmokeAgentArguments arguments

        @Inject
        AgentProbe(ObjectFactory objects) {
          arguments = objects.newInstance(SmokeAgentArguments)
        }

        @Nested
        SmokeAgentArguments getAgentArguments() { arguments }

        @OutputFile
        abstract RegularFileProperty getResult()

        @TaskAction
        void probe() {
          result.get().asFile.text = arguments.asArguments().join('\n')
        }
      }

      normalization {
        runtimeClasspath { ignore('**/*.version') }
      }
      def agentFile = layout.projectDirectory.file('agent.jar')
      def versionResources = fileTree('expected-resources')
      def versionDependency = layout.projectDirectory.file('dependency.jar')
      def version = providers.gradleProperty('tracerVersion').orElse('1.2.3-SNAPSHOT')
      def hash = providers.gradleProperty('commitHash').orElse('0123456789')
      def expectedVersion = version.zip(hash) { v, h -> "${'$'}v~${'$'}h" }
      def marker = layout.buildDirectory.file('verified')
      def verifyAgent = tasks.register('verifyAgent') {
        inputs.file(agentFile).withPathSensitivity(PathSensitivity.NONE)
        inputs.files(versionResources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(versionDependency).withPathSensitivity(PathSensitivity.NONE)
        inputs.property('expectedVersion', expectedVersion)
        outputs.file(marker)
        doLast {
          def expectedPaths = AgentVersionFiles.expectedPaths(versionResources, [versionDependency.asFile])
          new JarFile(agentFile.asFile).withCloseable { jar ->
            def failures = AgentVersionFiles.validate(jar, expectedPaths, expectedVersion.get())
            if (!failures.empty) throw new GradleException(failures.join('\n'))
          }
          marker.get().asFile.text = expectedPaths.sort().join('\n') + '\n'
        }
      }
      tasks.register('probe', AgentProbe) {
        inputs.files(verifyAgent).withPropertyName('verifiedAgentJar')
          .withPathSensitivity(PathSensitivity.NONE)
        agentArguments.tracerJar.set(agentFile)
        agentArguments.tracerVersion.set(version)
        agentArguments.diagnosticsJar.set(layout.projectDirectory.file('diagnostics.jar'))
        result.set(layout.buildDirectory.file('probe.txt'))
      }
      """,
    )
    writeJar("diagnostics.jar", mapOf("diagnostics.class" to "original"))
    writeAgent()
  }

  @Test
  fun `commit stamp changes reuse results after raw metadata verification`() {
    populateCache()
    writeAgent(hash = "abcdef0123")

    val result = run("probe", "--build-cache", "-PcommitHash=abcdef0123")

    assertThat(result.task(":verifyAgent")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":probe")?.outcome).isEqualTo(FROM_CACHE)
    assertThat(buildFile("probe.txt").readText()).contains(file("agent.jar").absolutePath)
  }

  @ParameterizedTest
  @ValueSource(strings = ["agent.classdata", "META-INF/MANIFEST.MF", "inst/instrumenter.index", "config.properties"])
  fun `agent implementation and resources invalidate results`(path: String) {
    populateCache()
    val content = if (path == "META-INF/MANIFEST.MF") "Manifest-Version: 1.0\nTest-Attribute: changed\n\n" else "changed"
    writeAgent(extra = mapOf(path to content))

    assertThat(run("probe", "--build-cache").task(":probe")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `project version changes invalidate results`() {
    populateCache()
    writeAgent(version = "2.0.0-SNAPSHOT")

    val result = run("probe", "--build-cache", "-PtracerVersion=2.0.0-SNAPSHOT")

    assertThat(result.task(":probe")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `diagnostics agent changes invalidate results`() {
    populateCache()
    writeJar("diagnostics.jar", mapOf("diagnostics.class" to "changed"))

    assertThat(run("probe", "--build-cache").task(":probe")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `missing version resource fails before cached results can be restored`() {
    populateCache()
    writeJar(
      "agent.jar",
      mapOf("agent.version" to "1.2.3-SNAPSHOT~0123456789", "agent.classdata" to "original"),
    )

    val result = run("probe", "--build-cache", expectFailure = true)

    assertThat(result.output).contains("Missing version resource: cws-tls/cws-tls.version")
    assertThat(result.task(":probe")).isNull()
  }

  @Test
  fun `new upstream version resource invalidates cached results`() {
    populateCache()
    writeFile("expected-resources/new-product/new-product.version", "1.2.3-SNAPSHOT~0123456789")
    writeAgent(extra = mapOf("new-product/new-product.version" to "1.2.3-SNAPSHOT~0123456789"))

    val result = run("probe", "--build-cache")

    assertThat(result.task(":probe")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `resource discovered upstream must be present in the assembled jar`() {
    populateCache()
    writeFile("expected-resources/new-product/new-product.version", "1.2.3-SNAPSHOT~0123456789")

    val result = run("probe", "--build-cache", expectFailure = true)

    assertThat(result.output).contains("Missing version resource: new-product/new-product.version")
    assertThat(result.task(":probe")).isNull()
  }

  @Test
  fun `removing an upstream version resource invalidates cached results`() {
    populateCache()
    writeJar("dependency.jar", emptyMap())
    writeJar(
      "agent.jar",
      mapOf("agent.version" to "1.2.3-SNAPSHOT~0123456789", "agent.classdata" to "original"),
    )

    val result = run("probe", "--build-cache")

    assertThat(result.task(":probe")?.outcome).isEqualTo(SUCCESS)
  }

  private fun populateCache() {
    val result = run("probe", "--build-cache")
    assertThat(result.task(":probe")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(buildFile("probe.txt").delete()).isTrue()
  }

  private fun writeAgent(
    version: String = "1.2.3-SNAPSHOT",
    hash: String = "0123456789",
    extra: Map<String, String> = emptyMap(),
  ) {
    writeFile("expected-resources/agent.version", "$version~$hash")
    writeJar("dependency.jar", mapOf("cws-tls/cws-tls.version" to "$version~$hash"))
    writeJar(
      "agent.jar",
      mapOf(
        "agent.version" to "$version~$hash",
        "cws-tls/cws-tls.version" to "$version~$hash",
        "agent.classdata" to "original",
      ) + extra,
    )
  }

  private fun writeJar(name: String, entries: Map<String, String>) {
    JarOutputStream(file(name).outputStream()).use { jar ->
      entries.forEach { (path, content) ->
        jar.putNextEntry(JarEntry(path))
        jar.write(content.toByteArray(Charsets.UTF_8))
        jar.closeEntry()
      }
    }
  }
}
