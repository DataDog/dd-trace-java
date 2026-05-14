package datadog.gradle.plugin

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

  // Configure Gradle to use as few resources as possible:
  //  - Xms64m -Xmx256m: consume minimum amount of RAM.
  //  - workers.max=1: don't let the daemon fan out into multiple Worker JVMs.
  //  - parallel=false: serialize task execution within the fixture build.
  // Re-applied if missing.
  private fun applyResourceLimits() {
    val gradleProperties = file("gradle.properties")
    if (gradleProperties.exists() && gradleProperties.readText().contains("org.gradle.jvmargs=-Xms64m -Xmx256m")) {
      return
    }

    writeFile("gradle.properties",
      """
      org.gradle.jvmargs=-Xms64m -Xmx256m
      org.gradle.workers.max=1
      org.gradle.parallel=false
      """,
      append = true,
    )
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
   * @param forwardOutput Forward the build's stdout/stderr to the test's output
   * @param projectDir Override the project directory used by Gradle (useful for git worktree
   *                   tests); when null, defaults to the fixture's project directory.
   * @return The build result
   */
  fun run(
    vararg args: String,
    expectFailure: Boolean = false,
    env: Map<String, String> = emptyMap(),
    forwardOutput: Boolean = false,
    projectDir: File? = null,
  ): BuildResult {
    applyResourceLimits()

    val runner = GradleRunner.create()
      .withTestKitDir(testKitDir)
      .withPluginClasspath()
      .withProjectDir(projectDir ?: this.projectDir)
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
   * Adds a subproject to the build by appending an `include` line to settings.gradle
   * and writing the subproject's build.gradle.
   *
   * @param projectPath The project path (e.g., "dd-java-agent:instrumentation:other")
   * @param buildScript The build script content for the subproject
   */
  fun addSubproject(projectPath: String, @Language("Groovy") buildScript: String) {
    writeFile("settings.gradle", "include ':$projectPath'", append = true)
    writeFile("${projectPath.replace(':', '/')}/build.gradle", buildScript)
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
   * Writes the root project's build.gradle file.
   *
   * @param buildScript The build script content for the root project
   * @param append If true, appends to any existing file instead of overwriting
   */
  fun writeRootProject(@Language("Groovy") buildScript: String, append: Boolean = false): File =
    writeFile("build.gradle", buildScript, append)

  /**
   * Writes the root project's settings.gradle file.
   *
   * @param settingsScript The settings script content
   * @param append If true, appends to any existing file instead of overwriting
   */
  fun writeSettings(@Language("Groovy") settingsScript: String, append: Boolean = false): File =
    writeFile("settings.gradle", settingsScript, append)

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
