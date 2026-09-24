package datadog.buildlogic.testcontainers

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class TestcontainersPluginTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `moving images are refreshed before cache lookup including configuration cache reuse`() {
    val manifests = AtomicReference(manifest("1"))
    val requests = AtomicInteger()
    val server = registry()
    server.createContext("/v2/") { exchange ->
      requests.incrementAndGet()
      val body = manifests.get().toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/vnd.oci.image.manifest.v1+json")
      exchange.responseHeaders.add("Docker-Content-Digest", digest(manifests.get()))
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      fixture("127.0.0.1:${server.address.port}/library/cassandra:4")
      assertThat(run("help").task(":help")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(run("verifyServiceSelection").task(":verifyServiceSelection")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(requests.get()).isZero()
      assertThat(run("test", "-PskipTests").task(":test")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
      assertThat(requests.get()).isZero()

      assertThat(run("test").task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(report()).contains("library/cassandra@${digest(manifests.get())}")
      val firstRequests = requests.get()
      val warm = run("test")
      assertThat(warm.output).contains("Reusing configuration cache")
      assertThat(warm.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
      assertThat(requests.get()).isGreaterThan(firstRequests)

      manifests.set(manifest("2"))
      val changed = run("test")
      assertThat(changed.output).contains("Reusing configuration cache")
      assertThat(changed.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(report()).contains("library/cassandra@${digest(manifests.get())}")

      directory.resolve("build").toFile().deleteRecursively()
      val restored = run("test")
      assertThat(restored.output).contains("Reusing configuration cache")
      assertThat(restored.task(":test")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
      assertThat(report()).contains("library/cassandra@${digest(manifests.get())}")

      assertThat(run("latestDepTest", "latestDepTestForkedTest").task(":latestDepTestForkedTest")?.outcome)
        .isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
      assertThat(directory.resolve("build/test-results/latestDepTest/TEST-ImageTest.xml").toFile().readText())
        .contains("library/cassandra@${digest(manifests.get())}")
      val requestsBeforeUnrelatedSuite = requests.get()
      assertThat(run("isolatedTest").task(":isolatedTest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(run("emptyTest").task(":emptyTest")?.outcome).isEqualTo(TaskOutcome.NO_SOURCE)
      assertThat(requests.get()).isEqualTo(requestsBeforeUnrelatedSuite)

      server.stop(0)
      assertThat(runner("test").buildAndFail().output)
        .contains("Cannot resolve test container image")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `hub prefix is resolved and pinned references require no registry`() {
    val digest = "sha256:${"a".repeat(64)}"
    fixture("cassandra@$digest")
    val result =
      runner("test")
        .withEnvironment(
          System.getenv() +
            ("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" to "mirror.example/team/"),
        ).build()
    assertThat(result.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report()).contains("mirror.example/team/cassandra@$digest")
    val changed =
      runner("test")
        .withEnvironment(
          System.getenv() +
            ("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" to "another.example/team/"),
        ).build()
    assertThat(changed.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report()).contains("another.example/team/cassandra@$digest")

    directory.resolve("build.gradle").toFile().appendText(
      """

      sourceSets.test.resources.srcDirs = ['lateResources']
      tasks.named('test') {
        environment 'TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX', 'task.example/'
      }
      """.trimIndent(),
    )
    assertThat(run("test").task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report()).contains("task.example/cassandra@$digest")
    Files.createDirectories(directory.resolve("lateResources"))
    directory
      .resolve("lateResources/testcontainers.properties")
      .toFile()
      .writeText("image.substitutor=untracked.CustomSubstitutor\n")
    assertThat(runner("test").buildAndFail().output)
      .contains("move image overrides into testContainerImage declarations")
  }

  @Test
  fun `container tasks share the legacy limit across projects and configuration cache reuse`() {
    fixture("cassandra@sha256:${"a".repeat(64)}")
    directory.resolve("settings.gradle").toFile().appendText("\ninclude('other')\n")
    directory.resolve("gradle.properties").toFile().writeText("testcontainersMaxParallelUsages=1\n")
    val buildFile = directory.resolve("build.gradle").toFile()
    buildFile.appendText(
      """

      tasks.withType(Test).configureEach {
        systemProperty('containerLock', '${directory.resolve("container.lock")}')
      }
      """.trimIndent(),
    )
    val testFile = directory.resolve("src/test/java/ImageTest.java").toFile()
    testFile.writeText(
      """
      import org.junit.jupiter.api.Test;
      import static org.junit.jupiter.api.Assertions.assertTrue;
      public class ImageTest {
        @Test public void respectsContainerLimit() throws Exception {
          java.io.File lock = new java.io.File(System.getProperty("containerLock"));
          assertTrue(lock.createNewFile(), "Container tasks exceeded the shared limit");
          try { Thread.sleep(1000); } finally { lock.delete(); }
        }
      }
      """.trimIndent(),
    )
    val other = directory.resolve("other")
    Files.createDirectories(other.resolve("src/test/java"))
    other.resolve("src/test/java/ImageTest.java").toFile().writeText(testFile.readText())
    // A legacy module still declares usesService explicitly and has no image declarations.
    other.resolve("build.gradle").toFile().writeText(
      buildFile
        .readText()
        .replace("id 'dd-trace-java.testcontainers'", "id 'dd-trace-java.testcontainers-limit'")
        .replace("testContainerImage(image('cassandra@sha256:${"a".repeat(64)}', 'test.cassandra.image'))", "") +
        """

        tasks.named('test', Test) { usesService(testcontainersLimit) }
        """.trimIndent(),
    )
    val arguments = arrayOf(":test", ":other:test", "--parallel", "--rerun-tasks", "--no-build-cache")
    val first = run(*arguments)
    assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(first.task(":other:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val reused = run(*arguments)
    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(reused.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(reused.task(":other:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `bearer authentication resolves manifests with an older HttpClient in buildSrc`() {
    val server = registry()
    val tokenRequests = AtomicInteger()
    val authorizedRequests = AtomicInteger()
    server.createContext("/token") { exchange ->
      tokenRequests.incrementAndGet()
      val body = """{"token":"fixture-token"}""".toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.createContext("/v2/") { exchange ->
      if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-token") {
        exchange.responseHeaders.add(
          "WWW-Authenticate",
          "Bearer realm=\"https://127.0.0.1:${server.address.port}/token\",service=\"fixture\",scope=\"repository:library/cassandra:pull\"",
        )
        exchange.sendResponseHeaders(401, -1)
        exchange.close()
      } else {
        authorizedRequests.incrementAndGet()
        val body = manifest("3").toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/vnd.oci.image.manifest.v1+json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
    }
    server.start()
    try {
      fixture("127.0.0.1:${server.address.port}/library/cassandra:4")
      // Reproduce the parent classloader supplied by Aether in the real buildSrc.
      val httpClasspath =
        System.getProperty("test.buildSrc.classpath").split(File.pathSeparator).joinToString(", ") { "'$it'" }
      Files.createDirectories(directory.resolve("buildSrc/src/main/java"))
      directory.resolve("buildSrc/src/main/java/BuildLogic.java").toFile().writeText("public class BuildLogic {}\n")
      directory.resolve("buildSrc/build.gradle").toFile().writeText(
        """
        plugins { id 'java' }
        dependencies { implementation files($httpClasspath) }
        """.trimIndent(),
      )
      // Load as an included build instead of using TestKit's injected plugin classpath.
      val metadata = Properties()
      javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { metadata.load(it) }
      val pluginClasspath = metadata.getProperty("implementation-classpath").split(File.pathSeparator).joinToString(", ") { "'$it'" }
      Files.createDirectories(directory.resolve("plugin"))
      directory.resolve("plugin/settings.gradle").toFile().writeText("rootProject.name = 'fixture-plugin'\n")
      directory.resolve("plugin/build.gradle").toFile().writeText(
        """
        plugins { id 'java-gradle-plugin' }
        dependencies { implementation files($pluginClasspath) }
        gradlePlugin {
          plugins {
            testcontainers {
              id = 'dd-trace-java.testcontainers'
              implementationClass = 'datadog.buildlogic.testcontainers.TestcontainersPlugin'
            }
          }
        }
        """.trimIndent(),
      )
      val settings = directory.resolve("settings.gradle").toFile()
      settings.writeText("pluginManagement { includeBuild('plugin') }\n" + settings.readText())
      assertThat(runner("test", injectPluginClasspath = false).build().task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(tokenRequests.get()).isPositive()
      assertThat(authorizedRequests.get()).isPositive()
      assertThat(report()).contains(digest(manifest("3")))
    } finally {
      server.stop(0)
    }
  }

  private fun fixture(image: String) {
    directory.resolve("settings.gradle").toFile().writeText(
      """
      rootProject.name = 'container-image-fixture'
      buildCache { local { directory = file('cache') } }
      """.trimIndent(),
    )
    val junitClasspath =
      listOf(
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.engine.JupiterTestEngine",
        "org.junit.platform.engine.TestEngine",
        "org.junit.platform.launcher.Launcher",
        "org.junit.platform.commons.JUnitException",
        "org.opentest4j.AssertionFailedError",
      ).joinToString(", ") {
        "'${Path.of(
          Class
            .forName(it)
            .protectionDomain.codeSource.location
            .toURI(),
        )}'"
      }
    directory.resolve("build.gradle").toFile().writeText(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.testcontainers'
      }
      sourceSets {
        latestDepTest { java.srcDirs = sourceSets.test.java.srcDirs }
        isolatedTest
        emptyTest
      }
      tasks.register('latestDepTest', Test) {
        testClassesDirs = sourceSets.latestDepTest.output.classesDirs
        classpath = sourceSets.latestDepTest.runtimeClasspath
      }
      tasks.register('latestDepTestForkedTest', Test) {
        testClassesDirs = sourceSets.latestDepTest.output.classesDirs
        classpath = sourceSets.latestDepTest.runtimeClasspath
      }
      tasks.register('isolatedTest', Test) {
        testClassesDirs = sourceSets.isolatedTest.output.classesDirs
        classpath = sourceSets.isolatedTest.runtimeClasspath
      }
      tasks.register('emptyTest', Test) {
        testClassesDirs = sourceSets.emptyTest.output.classesDirs
        classpath = sourceSets.emptyTest.runtimeClasspath
      }
      def skip = providers.gradleProperty('skipTests')
      tasks.withType(Test).configureEach {
        useJUnitPlatform()
        onlyIf { !skip.isPresent() }
      }
      // Plugins must see declarations and inheritance added after tasks are realized.
      tasks.named('test').get()
      tasks.named('latestDepTest').get()
      tasks.named('latestDepTestForkedTest').get()
      configurations.latestDepTestImplementation.extendsFrom(configurations.testImplementation)
      configurations.emptyTestImplementation.extendsFrom(configurations.testImplementation)
      dependencies {
        testImplementation files($junitClasspath)
        isolatedTestImplementation files($junitClasspath)
        testContainerImage(image('$image', 'test.cassandra.image'))
      }
      tasks.register('verifyServiceSelection') {
        def selected = providers.provider {
          ['test', 'latestDepTest', 'latestDepTestForkedTest', 'isolatedTest'].collectEntries { name ->
            def task = tasks.named(name).get()
            [(name): task.requiredServices.searchServices().any { it.name == 'testcontainersLimit' }]
          }
        }
        inputs.property('selected', selected)
        doLast {
          assert inputs.properties.selected == [test: true, latestDepTest: true, latestDepTestForkedTest: true, isolatedTest: false]
        }
      }
      """.trimIndent(),
    )
    Files.createDirectories(directory.resolve("src/test/java"))
    directory.resolve("src/test/java/ImageTest.java").toFile().writeText(
      """
      import org.junit.jupiter.api.Test;
      import static org.junit.jupiter.api.Assertions.assertTrue;
      public class ImageTest {
        @Test public void imageIsPinned() {
          String image = System.getProperty("test.cassandra.image");
          assertTrue(image.matches(".+@sha256:[a-f0-9]{64}"), image);
          System.out.println(image);
        }
      }
      """.trimIndent(),
    )
    Files.createDirectories(directory.resolve("src/isolatedTest/java"))
    directory.resolve("src/isolatedTest/java/PlainTest.java").toFile().writeText(
      """
      import org.junit.jupiter.api.Test;
      import static org.junit.jupiter.api.Assertions.assertNull;
      public class PlainTest {
        @Test public void hasNoContainerDependency() {
          assertNull(System.getProperty("test.cassandra.image"));
        }
      }
      """.trimIndent(),
    )
  }

  private fun runner(
    vararg arguments: String,
    injectPluginClasspath: Boolean = true,
  ) = GradleRunner
    .create()
    .withProjectDir(directory.toFile())
    .apply { if (injectPluginClasspath) withPluginClasspath() }
    .withArguments(
      *arguments,
      "--build-cache",
      "--configuration-cache",
      "--stacktrace",
      "--max-workers=2",
      "-Dorg.gradle.jvmargs=-Xmx512m",
    )

  private fun run(vararg arguments: String) = runner(*arguments).build()

  private fun report() = directory.resolve("build/test-results/test/TEST-ImageTest.xml").toFile().readText()

  private fun manifest(character: String) =
    """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"sha256:${character.repeat(
      64,
    )}","size":2},"layers":[]}"""

  private fun registry(): HttpsServer {
    val keystore = directory.resolve("registry.p12")
    val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
    val process =
      ProcessBuilder(
        keytool,
        "-genkeypair",
        "-alias",
        "registry",
        "-keyalg",
        "RSA",
        "-keystore",
        keystore.toString(),
        "-storepass",
        "fixture",
        "-keypass",
        "fixture",
        "-dname",
        "CN=localhost",
        "-validity",
        "1",
        "-noprompt",
      ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    val keys = KeyStore.getInstance("PKCS12")
    Files.newInputStream(keystore).use { keys.load(it, "fixture".toCharArray()) }
    val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    manager.init(keys, "fixture".toCharArray())
    val context = SSLContext.getInstance("TLS")
    context.init(manager.keyManagers, null, null)
    return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      httpsConfigurator = HttpsConfigurator(context)
    }
  }

  private fun digest(body: String) =
    "sha256:" +
      MessageDigest
        .getInstance("SHA-256")
        .digest(body.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
