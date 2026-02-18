package datadog.smoketest


import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.civisibility.CiVisibilitySmokeTest
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.TempDir

class JUnitConsoleSmokeTest extends CiVisibilitySmokeTest {
  // CodeNarc incorrectly thinks ".class" is unnecessary in getLogger
  @SuppressWarnings('UnnecessaryDotClass')
  private static final Logger LOGGER = LoggerFactory.getLogger(JUnitConsoleSmokeTest.class)

  private static final String TEST_SERVICE_NAME = "test-headless-service"

  private static final int PROCESS_TIMEOUT_SECS = 60
  private static final String JUNIT_CONSOLE_JAR_PATH = System.getProperty("datadog.smoketest.junit.console.jar.path")
  private static final String JAVA_HOME = buildJavaHome()

  @TempDir
  Path projectHome

  @Shared
  @AutoCleanup
  MockBackend mockBackend = new MockBackend()

  def setup() {
    mockBackend.reset()
  }

  def "test headless failed test replay"() {
    givenProjectFiles(projectName)

    mockBackend.givenFlakyRetries(true)
    mockBackend.givenFlakyTest("test-headless-service", "com.example.TestFailed", "test_failed")
    mockBackend.givenFailedTestReplay(true)

    def compileCode = compileTestProject()
    assert compileCode == 0

    def exitCode = whenRunningJUnitConsole([
      (CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_COUNT): "3",
      (GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL): mockBackend.intakeUrl
    ],
    [:])
    assert exitCode == 1

    def additionalDynamicTags = ["content.meta.['_dd.debug.error.6.snapshot_id']", "content.meta.['_dd.debug.error.exception_id']"]
    verifyEventsAndCoverages(projectName, "junit-console", "headless", mockBackend.waitForEvents(7), mockBackend.waitForCoverages(0), additionalDynamicTags)
    verifySnapshots(mockBackend.waitForLogs(2), 2)

    where:
    projectName = "test_junit_console_failed_test_replay"
  }

  private void givenProjectFiles(String projectFilesSources) {
    def projectResourcesUri = this.getClass().getClassLoader().getResource(projectFilesSources).toURI()
    def projectResourcesPath = Paths.get(projectResourcesUri)
    copyFolder(projectResourcesPath, projectHome)
  }

  private void copyFolder(Path src, Path dest) throws IOException {
    Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
          Files.createDirectories(dest.resolve(src.relativize(dir)))
          return FileVisitResult.CONTINUE
        }

        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
        throws IOException {
          Files.copy(file, dest.resolve(src.relativize(file)))
          return FileVisitResult.CONTINUE
        }
      })

    // creating empty .git directory so that the tracer could detect projectFolder as repo root
    Files.createDirectory(projectHome.resolve(".git"))
  }

  private int compileTestProject() {
    def srcDir = projectHome.resolve("src/main/java")
    def testSrcDir = projectHome.resolve("src/test/java")
    def classesDir = projectHome.resolve("target/classes")
    def testClassesDir = projectHome.resolve("target/test-classes")

    Files.createDirectories(classesDir)
    Files.createDirectories(testClassesDir)

    // Compile main classes if they exist
    if (Files.exists(srcDir)) {
      def mainJavaFiles = findJavaFiles(srcDir)
      if (!mainJavaFiles.isEmpty()) {
        def result = runProcess(createCompilerProcessBuilder(classesDir.toString(), mainJavaFiles).start())
        if (result != 0) {
          LOGGER.error("Error compiling source classes for JUnit Console smoke test")
          return result
        }
      }
    }

    // Compile test classes
    def testJavaFiles = findJavaFiles(testSrcDir)
    if (!testJavaFiles.isEmpty()) {
      def result = runProcess(createCompilerProcessBuilder(testClassesDir.toString(), testJavaFiles, [classesDir.toString()]).start())
      if (result != 0) {
        LOGGER.error("Error compiling source classes for JUnit Console smoke test")
        return result
      }
    }

    return 0
  }

  private ProcessBuilder createCompilerProcessBuilder(String targetDir, List<String> files, List<String> additionalDeps = []) {
    assert new File(JUNIT_CONSOLE_JAR_PATH).isFile()

    List<String> deps = [JUNIT_CONSOLE_JAR_PATH]
    deps.addAll(additionalDeps)

    List<String> command = new ArrayList<>()
    command.add(javacPath())
    command.addAll(["-cp", deps.join(":")])
    command.addAll(["-d", targetDir])
    command.addAll(files)

    ProcessBuilder processBuilder = new ProcessBuilder(command)

    processBuilder.directory(projectHome.toFile())
    processBuilder.environment().put("JAVA_HOME", JAVA_HOME)

    return processBuilder
  }

  private static List<String> findJavaFiles(Path directory) {
    if (!Files.exists(directory)) {
      return []
    }

    List<String> javaFiles = []
    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(".java")) {
            javaFiles.add(file.toString())
          }
          return FileVisitResult.CONTINUE
        }
      })

    return javaFiles
  }

  private int whenRunningJUnitConsole(Map<String, String> additionalAgentArgs, Map<String, String> additionalEnvVars) {
    def processBuilder = createConsoleProcessBuilder(["execute"], additionalAgentArgs, additionalEnvVars)

    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF")

    return runProcess(processBuilder.start())
  }

  private static runProcess(Process p, int timeoutSecs = PROCESS_TIMEOUT_SECS) {
    StreamConsumer errorGobbler = new StreamConsumer(p.getErrorStream(), "ERROR")
    StreamConsumer outputGobbler = new StreamConsumer(p.getInputStream(), "OUTPUT")
    outputGobbler.start()
    errorGobbler.start()

    if (!p.waitFor(timeoutSecs, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      throw new TimeoutException("Instrumented process failed to exit within $timeoutSecs  seconds")
    }

    return p.exitValue()
  }

  ProcessBuilder createConsoleProcessBuilder(List<String> consoleCommand, Map<String, String> additionalAgentArgs, Map<String, String> additionalEnvVars) {
    assert new File(JUNIT_CONSOLE_JAR_PATH).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.add("-Ddatadog.slf4j.simpleLogger.defaultLogLevel=DEBUG")
    command.addAll((String[]) ["-jar", JUNIT_CONSOLE_JAR_PATH])
    command.addAll(consoleCommand)
    command.addAll([
      "--class-path",
      [projectHome.resolve("target/classes").toString(), projectHome.resolve("target/test-classes")].join(":")
    ])
    command.add("--scan-class-path")

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(projectHome.toFile())

    processBuilder.environment().put("JAVA_HOME", JAVA_HOME)
    processBuilder.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions(additionalAgentArgs))
    for (envVar in additionalEnvVars) {
      processBuilder.environment().put(envVar.key, envVar.value)
    }

    def mavenRepositoryProxy = System.getenv("MAVEN_REPOSITORY_PROXY")
    if (mavenRepositoryProxy != null) {
      processBuilder.environment().put("MAVEN_REPOSITORY_PROXY", mavenRepositoryProxy)
    }

    return processBuilder
  }

  String javaToolOptions(Map<String, String> additionalAgentArgs) {
    additionalAgentArgs.put(CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED, "false")
    return buildJvmArguments(mockBackend.intakeUrl, TEST_SERVICE_NAME, additionalAgentArgs).join(" ")
  }

  private static class StreamConsumer extends Thread {
    final InputStream is
    final String messagePrefix

    StreamConsumer(InputStream is, String messagePrefix) {
      this.is = is
      this.messagePrefix = messagePrefix
    }

    @Override
    void run() {
      try {
        BufferedReader br = new BufferedReader(new InputStreamReader(is))
        String line
        while ((line = br.readLine()) != null) {
          System.out.println(messagePrefix + ": " + line)
        }
      } catch (IOException e) {
        e.printStackTrace()
      }
    }
  }
}
