package datadog.gradle.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.intellij.lang.annotations.Language
import org.w3c.dom.Document
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Base fixture for Gradle plugin integration tests.
 * Provides common functionality for setting up test projects and running Gradle builds.
 */
internal open class GradleFixture(protected val projectDir: File) {
  // Each fixture gets its own testkit dir in the system temp directory (NOT under
  // projectDir) so that JUnit's @TempDir cleanup doesn't race with daemon file locks.
  // See https://github.com/gradle/gradle/issues/12535
  // A fresh daemon is started per test — ensuring withEnvironment() vars (e.g.
  // MAVEN_REPOSITORY_PROXY) are correctly set on the daemon JVM and not inherited
  // from a previously-started daemon with a different test's environment.
  // A JVM shutdown hook removes the directory after all tests have run (and daemons
  // have been stopped), so file locks are guaranteed to be released by then.
  private val testKitDir: File by lazy {
    Files.createTempDirectory("gradle-testkit-").toFile().also { dir ->
      Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }
  }

  /**
   * Runs Gradle with the specified arguments.
   *
   * After the build completes, any Gradle daemons started by TestKit are killed
   * so their file locks on the testkit cache are released before JUnit `@TempDir`
   * cleanup.  See https://github.com/gradle/gradle/issues/12535
   *
   * @param args Gradle task names and arguments
   * @param expectFailure Whether the build is expected to fail
   * @param env Environment variables to set (merged with system environment)
   * @return The build result
   */
  fun run(vararg args: String, expectFailure: Boolean = false, env: Map<String, String> = emptyMap()): BuildResult {
    val runner = GradleRunner.create()
      .withTestKitDir(testKitDir)
      .withPluginClasspath()
      .withProjectDir(projectDir)
      // Using withDebug prevents starting a daemon, but it doesn't work with withEnvironment
      .withEnvironment(System.getenv() + env)
      .withArguments(*args)
    return try {
      if (expectFailure) runner.buildAndFail() else runner.build()
    } catch (e: UnexpectedBuildResultException) {
      e.buildResult
    } finally {
      stopDaemons()
    }
  }

  /**
   * Kills Gradle daemons started by TestKit for this fixture's testkit dir.
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
   *   could theoretically recycle the PID.  In practice the window is short
   *   (the `finally` block runs immediately after the build) so the risk is negligible.
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
  private fun stopDaemons() {
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

  /**
   * Adds a subproject to the build.
   * Updates settings.gradle and creates the build script for the subproject.
   *
   * @param projectPath The project path (e.g., "dd-java-agent:instrumentation:other")
   * @param buildScript The build script content for the subproject
   */
  fun addSubproject(projectPath: String, @Language("Groovy") buildScript: String) {
    // Add to settings.gradle
    val settingsFile = file("settings.gradle")
    if (settingsFile.exists()) {
      settingsFile.appendText("\ninclude ':$projectPath'")
    } else {
      settingsFile.writeText("include ':$projectPath'")
    }

    file("${projectPath.replace(':', '/')}/build.gradle")
      .writeText(buildScript.trimIndent())
  }

  /**
   * Writes the root project's build.gradle file.
   *
   * @param buildScript The build script content for the root project
   */
  fun writeRootProject(@Language("Groovy") buildScript: String) {
    file("build.gradle").writeText(buildScript.trimIndent())
  }

  /**
   * Parses an XML file into a DOM Document.
   */
  fun parseXml(xmlFile: File): Document {
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    return builder.parse(xmlFile)
  }

  /**
   * Creates or gets a file in the project directory, ensuring parent directories exist.
   */
  protected fun file(path: String): File =
    File(projectDir, path).also { file ->
      file.parentFile?.mkdirs()
    }
}
