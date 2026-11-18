package datadog.buildlogic.smoketest

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NestedGradleDistributionTest {

  @TempDir
  lateinit var projectDir: Path

  private val requests = AtomicInteger()
  private val status = AtomicInteger(200)
  private val barrier = CountDownLatch(2)
  private val firstFinished = CountDownLatch(1)
  private val executor = Executors.newCachedThreadPool()
  private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
    executor = this@NestedGradleDistributionTest.executor
    createContext("/internal/artifact/services.gradle.org/distributions/gradle-$DEFAULT_NESTED_GRADLE_VERSION-bin.zip") { exchange ->
      requests.incrementAndGet()
      val expected = "Basic " + Base64.getEncoder().encodeToString("smoke:secret".toByteArray())
      val response = if (exchange.requestHeaders.getFirst("Authorization") == expected) status.get() else 401
      try {
        if (response == 200) {
          exchange.sendResponseHeaders(response, distributionArchive.length())
          exchange.responseBody.use { output -> distributionArchive.inputStream().use { it.copyTo(output) } }
        } else {
          exchange.sendResponseHeaders(response, -1)
        }
      } finally {
        exchange.close()
      }
    }
    createContext("/barrier") { exchange ->
      barrier.countDown()
      exchange.sendResponseHeaders(if (barrier.await(60, TimeUnit.SECONDS)) 200 else 500, -1)
      exchange.close()
    }
    createContext("/first-finished") { exchange ->
      firstFinished.countDown()
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    }
    createContext("/wait-first-finished") { exchange ->
      exchange.sendResponseHeaders(if (firstFinished.await(60, TimeUnit.SECONDS)) 200 else 500, -1)
      exchange.close()
    }
    start()
  }

  private val baseUrl get() = "http://smoke:secret@localhost:${server.address.port}"

  @AfterEach
  fun stopServer() {
    server.stop(0)
    executor.shutdownNow()
  }

  @Test
  fun `parallel tasks and later outer builds reuse one verified distribution`() {
    writeProjects("first", "second")

    val first = runner(":first:buildApp", ":second:buildApp", "--parallel", "--max-workers=2", "--rerun-tasks", "--no-build-cache", "-Poverlap=true").build()

    assertThat(first.task(":first:buildApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(first.task(":second:buildApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(requests).hasValue(1)
    val firstState = state("first")
    val secondState = state("second")
    assertThat(firstState.getProperty("version")).isEqualTo(DEFAULT_NESTED_GRADLE_VERSION)
    assertThat(secondState.getProperty("version")).isEqualTo(DEFAULT_NESTED_GRADLE_VERSION)
    assertThat(firstState.getProperty("home")).isEqualTo(secondState.getProperty("home"))
    assertThat(File(firstState.getProperty("home"))).isDirectory
    assertStopped(firstState)
    assertStopped(secondState)

    status.set(503)
    val second = runner(":first:buildApp", ":second:buildApp", "--parallel", "--max-workers=2", "--rerun-tasks", "--no-build-cache", "-Poverlap=true").build()

    assertThat(second.output).contains("Reusing configuration cache")
    assertThat(requests).hasValue(1)
    assertThat(state("first").getProperty("version")).isEqualTo(DEFAULT_NESTED_GRADLE_VERSION)
    assertThat(state("first").getProperty("home")).isEqualTo(firstState.getProperty("home"))
    assertThat(File(firstState.getProperty("installation"))).isDirectory
    assertStopped(state("first"))
    assertStopped(state("second"))
  }

  @Test
  fun `checksum mismatch prevents the application from running`() {
    writeProjects("app", checksum = "0".repeat(64))

    val result = runner(":app:buildApp").buildAndFail()

    assertThat(result.output).contains("Verification of Gradle distribution failed")
    assertThat(requests).hasValue(1)
    assertThat(stateFile("app")).doesNotExist()
  }

  @Test
  fun `503 fails bootstrap and a later outer build can download again`() {
    writeProjects("app")
    status.set(503)

    val failed = runner(":app:buildApp").buildAndFail()

    assertThat(failed.output).contains("503")
    assertThat(requests).hasValue(1)
    assertThat(stateFile("app")).doesNotExist()

    status.set(200)
    val recovered = runner(":app:buildApp").build()

    assertThat(recovered.task(":app:buildApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(requests).hasValue(2)
    assertThat(stateFile("app")).exists()
    assertStopped(state("app"))
  }

  @Test
  fun `application failures run once`() {
    writeProjects("app", failApplication = true)

    val result = runner(":app:buildApp").buildAndFail()

    assertThat(result.output).contains("application failure")
    assertThat(projectDir.resolve("app/invocations.txt").toFile().readLines()).containsExactly("run")
    assertThat(requests).hasValue(1)
    assertStopped(state("app"))
  }

  @Test
  fun `outer task cache restores only application outputs`() {
    writeProjects("app")
    val testKitDir = projectDir.resolve("test-kit").toFile()
    val first = runner(":app:buildApp", "--build-cache").withTestKitDir(testKitDir).build()
    assertThat(first.task(":app:buildApp")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val retainedHome = File(state("app").getProperty("home"))
    val installation = File(state("app").getProperty("installation"))
    assertStopped(state("app"))
    assertThat(retainedHome).isDirectory
    assertThat(retainedHome.deleteRecursively()).isTrue()
    assertThat(projectDir.resolve("app/build").toFile().deleteRecursively()).isTrue()

    val restored = runner(":app:buildApp", "--build-cache").withTestKitDir(testKitDir).build()

    assertThat(restored.task(":app:buildApp")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(stateFile("app")).exists()
    assertThat(retainedHome).doesNotExist()
    assertThat(installation).doesNotExist()
    assertThat(requests).hasValue(1)
  }

  private fun writeProjects(
    vararg names: String,
    checksum: String = DEFAULT_NESTED_GRADLE_SHA256_SUM,
    failApplication: Boolean = false,
  ) {
    projectDir.resolve("settings.gradle").toFile().writeText(
      """
      rootProject.name = 'nested-distribution-fixture'
      include ${names.joinToString(", ") { "'$it'" }}
      buildCache { local { directory = new File(rootDir, 'task-cache') } }
      """.trimIndent(),
    )
    projectDir.resolve("build.gradle").toFile().writeText(
      "plugins { id 'dd-trace-java.smoke-test-app' apply false }\n",
    )
    names.forEachIndexed { index, name ->
      val directory = projectDir.resolve(name).toFile().apply { mkdirs() }
      val taskDeclaration = if (index == 0) {
        """
        smokeTestApp {
          gradleDistributionBaseUrl.set('$baseUrl')
          gradleDistributionSha256Sum.set('$checksum')
          gradleApp {
            taskName.set('buildApp')
            artifactPath.set('state.properties')
            sysProperty.set('state.path')
          }
        }
        """.trimIndent()
      } else {
        """
        tasks.register('buildApp', NestedGradleBuild) {
          applicationDir.set(layout.projectDirectory.dir('application'))
          applicationBuildDir.set(layout.buildDirectory.dir('application'))
          gradleDistributionBaseUrl.set('$baseUrl')
          gradleDistributionSha256Sum.set('$checksum')
          tasksToRun.set(['buildApp'])
          buildArguments.set([])
          environment.set([:])
        }
        """.trimIndent()
      }
      directory.resolve("build.gradle").writeText(
        """
        import datadog.buildlogic.smoketest.NestedGradleBuild
        plugins { id 'java'; id 'dd-trace-java.smoke-test-app' }
        $taskDeclaration
        tasks.withType(NestedGradleBuild).configureEach {
          gradleProperties.put('overlap', providers.gradleProperty('overlap').orElse('false'))
        }
        ${if (name == "first") "tasks.named('buildApp') { doLast { new URL('http://localhost:${server.address.port}/first-finished').text } }" else ""}
        """.trimIndent(),
      )
      val application = directory.resolve("application").apply { mkdirs() }
      application.resolve("settings.gradle").writeText("rootProject.name = '$name'\n")
      application.resolve("build.gradle").writeText(
        """
        layout.buildDirectory.set(file(providers.gradleProperty('appBuildDir').get()))
        tasks.register('buildApp') {
          def output = layout.buildDirectory.file('state.properties').get().asFile
          def overlap = providers.gradleProperty('overlap').get() == 'true'
          outputs.file(output)
          doLast {
            new File(rootDir.parentFile, 'invocations.txt').append('run\n')
            if (overlap) {
              new URL('http://localhost:${server.address.port}/barrier').text
              ${if (name == "second") "new URL('http://localhost:${server.address.port}/wait-first-finished').text" else ""}
            }
            output.parentFile.mkdirs()
            def state = new Properties()
            state.setProperty('home', gradle.gradleUserHomeDir.absolutePath)
            state.setProperty('installation', gradle.gradleHomeDir.absolutePath)
            state.setProperty('version', gradle.gradleVersion)
            state.setProperty('pid', ProcessHandle.current().pid().toString())
            def home = gradle.gradleUserHomeDir
            def pid = ProcessHandle.current().pid()
            Runtime.getRuntime().addShutdownHook(new Thread({
              Thread.sleep(2500)
              new File(home, 'stopped-' + pid + '.txt').text = 'stopped'
            }))
            output.withOutputStream { state.store(it, null) }
            ${if (failApplication) "throw new GradleException('application failure')" else ""}
          }
        }
        """.trimIndent(),
      )
    }
  }

  private fun stateFile(name: String): File = projectDir.resolve("$name/build/application/state.properties").toFile()

  private fun state(name: String): Properties = Properties().apply {
    stateFile(name).inputStream().use { load(it) }
  }

  private fun assertStopped(state: Properties) {
    val pid = state.getProperty("pid").toLong()
    assertThat(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)).isFalse()
    assertThat(File(state.getProperty("home"), "stopped-$pid.txt").readText()).isEqualTo("stopped")
  }

  private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(projectDir.toFile())
    .withPluginClasspath()
    .withArguments(*arguments, "--configuration-cache", "--stacktrace")
    .withEnvironment(
      System.getenv() + mapOf("GRADLE_ARGS" to "", "GRADLE_OPTS" to "", "MASS_READ_URL" to baseUrl),
    )
    .forwardOutput()

  companion object {
    @BeforeAll
    @JvmStatic
    fun downloadDistribution() {
      // Finish the external download before Gradle starts its local HTTP request timeout.
      assertThat(distributionArchive).isFile
    }

    private val distributionArchive: File by lazy {
      Files.createTempFile("nested-gradle-$DEFAULT_NESTED_GRADLE_VERSION-", ".zip").toFile().apply {
        deleteOnExit()
        val distribution = gradleDistributionUri(System.getenv(MASS_READ_URL_ENV), DEFAULT_NESTED_GRADLE_VERSION)
        // Keep credentials in the header so download failures do not expose them in the URL.
        URI(distribution.scheme, null, distribution.host, distribution.port, distribution.path, distribution.query, distribution.fragment)
          .toURL().openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            distribution.userInfo?.let { credentials ->
              setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8)))
            }
          }.getInputStream().use { input -> outputStream().use { input.copyTo(it) } }
      }
    }
  }
}
