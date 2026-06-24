package datadog.gradle.plugin

import datadog.gradle.plugin.GradleFixture.Companion.sharedTestKitDir
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Base fixture for Gradle plugin integration tests.
 * Provides common functionality for setting up test projects and running Gradle builds.
 */
open class GradleFixture {
  @TempDir
  protected lateinit var projectDir: File

  private val testKitDir: File get() = sharedTestKitDir

  companion object {
    // JVM-wide testkit dir shared across all GradleFixture instances. One daemon
    // pool serves every test method, so kotlinc work on .gradle.kts scripts is
    // amortized instead of re-paid per test (recovers the +77 % wall-time
    // regression introduced by the Groovy→Kotlin DSL conversion).
    //
    // Lives outside any @TempDir so JUnit cleanup never races with daemon file
    // locks. See https://github.com/gradle/gradle/issues/12535
    //
    // TestKit may reuse the same daemon for builds with different withEnvironment()
    // values, so build logic must not cache environment-derived state in daemon-static
    // fields.
    private val sharedTestKitDir: File by lazy {
      Files.createTempDirectory("gradle-testkit-").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread {
          stopDaemonsIn(dir)
          dir.deleteRecursively()
        })
      }
    }

    /**
     * Kills Gradle daemons started by TestKit under the given testkit dir.
     *
     * The Gradle Tooling API (used by [GradleRunner]) always spawns a daemon and
     * provides no public API to stop it (https://github.com/gradle/gradle/issues/12535).
     * We replicate the strategy Gradle uses in its own integration tests
     * ([DaemonLogsAnalyzer.killAll()][1]):
     *
     * 1. Scan `<testkit>/daemon/<version>/` for log files matching
     *    `DaemonLogConstants.DAEMON_LOG_PREFIX + pid + DaemonLogConstants.DAEMON_LOG_SUFFIX`,
     *    i.e. `daemon-<pid>.out.log`.
     * 2. Extract the PID from the filename and kill the process.
     *
     * Trade-offs of the PID-from-filename approach:
     * - **PID recycling**: between the build finishing and `kill` being sent, the OS
     *   could theoretically recycle the PID.  Now that this only runs at JVM exit
     *   (no longer per-test), the window is short — when called from the shutdown
     *   hook all daemons we own are still alive — so the risk remains negligible.
     * - **Filename convention is internal**: Gradle's `DaemonLogConstants.DAEMON_LOG_PREFIX`
     *   (`"daemon-"`) / `DAEMON_LOG_SUFFIX` (`".out.log"`) are not public API; a future
     *   Gradle version could change them.  The `toLongOrNull()` guard safely skips entries
     *   that don't parse as a PID (including the UUID fallback Gradle uses when the PID
     *   is unavailable).
     * - **Java 8 compatible**: uses `kill`/`taskkill` via [ProcessBuilder] instead of
     *   `ProcessHandle` (Java 9+) because build logic targets JVM 1.8.
     *
     * [1]: https://github.com/gradle/gradle/blob/43b381d88/testing/internal-distribution-testing/src/main/groovy/org/gradle/integtests/fixtures/daemon/DaemonLogsAnalyzer.groovy
     */
    private fun stopDaemonsIn(testKitDir: File) {
      val daemonDir = File(testKitDir, "daemon")
      if (!daemonDir.exists()) return

      daemonDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".out.log") && !it.name.startsWith("hs_err") }
        .forEach { logFile ->
          val pid = logFile.nameWithoutExtension  // daemon-12345.out
            .removeSuffix(".out")                 // daemon-12345
            .removePrefix("daemon-")              // 12345
            .toLongOrNull() ?: return@forEach     // skip UUIDs / unparseable names

          val isWindows = System.getProperty("os.name").lowercase().contains("win")
          val killProcess = if (isWindows) {
            ProcessBuilder("taskkill", "/F", "/PID", pid.toString())
          } else {
            ProcessBuilder("kill", pid.toString())
          }
          try {
            val process = killProcess.redirectErrorStream(true).start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
          } catch (_: Exception) {
            // best effort — daemon may already be stopped
          }
        }
    }
  }

  /**
   * Runs Gradle with the specified arguments.
   *
   * The TestKit daemon spawned by the first call and reused for every subsequent
   * call in the JVM (shared [testKitDir]) so Kotlin compilation of `.gradle.kts`
   * scripts amortizes across tests instead of being re-paid per test.
   * Daemons are reaped at JVM shutdown by the hook registered when
   * [sharedTestKitDir] is created.
   *
   * @param args Gradle task names and arguments
   * @param expectFailure Whether the build is expected to fail
   * @param env Environment variables to set (merged with system environment)
   * @param forwardOutput Forward the build's stdout/stderr to the test's output
   * @param gradleProjectDir Override the project directory used by Gradle (useful for git worktree tests);
   *     defaults to the fixture's project directory.
   * @return The build result
   */
  fun run(
    vararg args: String,
    expectFailure: Boolean = false,
    env: Map<String, String> = emptyMap(),
    forwardOutput: Boolean = false,
    gradleProjectDir: File = projectDir,
  ): BuildResult {
    val runner = GradleRunner.create()
      .withTestKitDir(testKitDir)
      .withPluginClasspath()
      .withProjectDir(gradleProjectDir)
      // Using withDebug prevents starting a daemon, but it doesn't work with withEnvironment
      .withEnvironment(System.getenv() + env)
      .withArguments(*args)
    if (forwardOutput) {
      runner.forwardOutput()
    }
    return try {
      if (expectFailure) runner.buildAndFail() else runner.build()
    } catch (e: UnexpectedBuildResultException) {
      e.buildResult
    }
  }

  /**
   * Writes a file under the project directory, creating parent dirs as needed.
   *
   * @param path Path relative to the project directory
   * @param content File contents; passed through [String.trimIndent] before writing,
   *                and a trailing newline is appended.
   * @param append If true, appends to any existing file instead of overwriting it.
   *               Safe to call repeatedly to build content up across steps.
   */
  fun writeFile(path: String, content: String, append: Boolean = false): File =
    file(path).also {
      it.parentFile?.mkdirs()
      val text = content.trimIndent() + "\n"
      if (append) it.appendText(text) else it.writeText(text)
    }

  /**
   * Adds a subproject to the build by appending an `include` line to settings.gradle.kts
   * and writing the subproject's build.gradle.kts.
   *
   * @param projectPath The project path (e.g., "dd-java-agent:instrumentation:other")
   * @param buildScript The build script content for the subproject
   */
  fun addSubproject(projectPath: String, @Language("kotlin") buildScript: String) {
    writeFile("settings.gradle.kts", """include(":$projectPath")""", append = true)
    writeFile("${projectPath.replace(':', '/')}/build.gradle.kts", buildScript)
  }

  /**
   * Writes a Java source file under src/<sourceSet>/java.
   *
   * @param classNameOrPath Simple class name, fully qualified class name, or source path
   * @param sourceCode The Java source content
   * @param sourceSet The Gradle source set to write to
   * @param projectPath Optional Gradle project path; defaults to the root project
   */
  fun writeJavaSource(
    classNameOrPath: String,
    @Language("JAVA") sourceCode: String,
    sourceSet: String = "main",
    projectPath: String? = null,
  ) {
    val sourcePath = classNameOrPath.removeSuffix(".java").replace('.', '/') + ".java"
    val projectPrefix = projectPath
      ?.removePrefix(":")
      ?.replace(':', '/')
      ?.let { "$it/" }
      .orEmpty()
    writeFile("${projectPrefix}src/$sourceSet/java/$sourcePath", sourceCode)
  }

  /**
   * Writes gradle.properties at the project root.
   *
   * @param content Properties content (trimIndent applied, trailing newline added)
   * @param append If true, appends to any existing file instead of overwriting
   */
  fun writeGradleProperties(content: String, append: Boolean = false): File =
    writeFile("gradle.properties", content, append)

  /**
   * Writes the root project's build.gradle.kts file.
   *
   * @param buildScript The build script content for the root project
   * @param append If true, appends to any existing file instead of overwriting
   */
  fun writeRootProject(@Language("kotlin") buildScript: String, append: Boolean = false): File =
    writeFile("build.gradle.kts", buildScript, append)

  /**
   * Writes the root project's settings.gradle.kts file.
   *
   * @param settingsScript The settings script content
   * @param append If true, appends to any existing file instead of overwriting
   */
  fun writeSettings(@Language("kotlin") settingsScript: String, append: Boolean = false): File =
    writeFile("settings.gradle.kts", settingsScript, append)

  /**
   * Parses an XML file into a DOM Document.
   */
  fun parseXml(xmlFile: File): Document {
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    return builder.parse(xmlFile)
  }

  /**
   * Returns a File handle under the project directory.
   * Does not touch the filesystem.
   */
  fun file(path: String): File = File(projectDir, path)

  /**
   * Creates a directory under the project directory (including any missing parents)
   * and returns it.
   */
  fun dir(path: String): File = file(path).also { it.mkdirs() }

  /**
   * The Gradle build output directory (`projectDir/build`). Not created — Gradle
   * produces it during a build.
   */
  val buildDir: File get() = File(projectDir, "build")

  /**
   * Returns a File under the Gradle build output directory (`projectDir/build/...`).
   * Does NOT create parent dirs — these paths are read after a Gradle build produces them.
   */
  fun buildFile(path: String): File = File(buildDir, path)
}
