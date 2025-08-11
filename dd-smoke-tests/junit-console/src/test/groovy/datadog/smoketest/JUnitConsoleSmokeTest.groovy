package datadog.smoketest


import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.civisibility.CiVisibilitySmokeTest
import datadog.trace.util.Strings
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
import spock.util.environment.Jvm

class JUnitConsoleSmokeTest extends CiVisibilitySmokeTest {
  // CodeNarc incorrectly thinks ".class" is unnecessary in getLogger
  @SuppressWarnings('UnnecessaryDotClass')
  private static final Logger LOGGER = LoggerFactory.getLogger(JUnitConsoleSmokeTest.class)

  private static final String TEST_SERVICE_NAME = "test-headless-service"
  private static final String TEST_ENVIRONMENT_NAME = "integration-test"

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
    mockBackend.givenFailedTestReplay(true)

    def compileCode = compileTestProject()
    assert compileCode == 0

    def exitCode = whenRunningJUnitConsole([
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_COUNT)}=2" as String,
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL)}=${mockBackend.intakeUrl}" as String
    ],
    [:])
    assert exitCode == 1

    def additionalDynamicTags = ["content.meta.['_dd.debug.error.6.snapshot_id']", "content.meta.['_dd.debug.error.exception_id']"]
    verifyEventsAndCoverages(projectName, "junit-console", "headless", mockBackend.waitForEvents(5), mockBackend.waitForCoverages(0), additionalDynamicTags)
    //TODO: add verification of the logs payload
    //mockBackend.waitForLogs(8)

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
    command.addAll(["-d", targetDir]) // TODO: check dir exists
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

  private int whenRunningJUnitConsole(List<String> additionalAgentArgs, Map<String, String> additionalEnvVars) {
    def processBuilder = createConsoleProcessBuilder(["execute"], additionalAgentArgs, additionalEnvVars)

    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF")

    return runProcess(processBuilder.start())
  }

  private static runProcess(Process p, int timeoutSecs = PROCESS_TIMEOUT_SECS) {
    StreamConsumer errorGobbler = new StreamConsumer(p.getErrorStream(), "ERROR")
    StreamConsumer outputGobbler = new StreamConsumer(p.getInputStream(), "OUTPUT")
    outputGobbler.start()
    errorGobbler.start()

    if (!p.waitFor(timeoutSecs , TimeUnit.SECONDS)) {
      p.destroyForcibly()
      throw new TimeoutException("Instrumented process failed to exit within $timeoutSecs  seconds")
    }

    return p.exitValue()
  }

  ProcessBuilder createConsoleProcessBuilder(List<String> consoleCommand, List<String> additionalAgentArgs, Map<String, String> additionalEnvVars) {
    assert new File(JUNIT_CONSOLE_JAR_PATH).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
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

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return JAVA_HOME + separator + "bin" + separator + "java"
  }

  String javacPath() {
    final String separator = System.getProperty("file.separator")
    return JAVA_HOME + separator + "bin" + separator + "javac"
  }

  String javaToolOptions(List<String> additionalAgentArgs) {
    def arguments = []

    if (System.getenv("DD_CIVISIBILITY_SMOKETEST_DEBUG_PARENT") != null) {
      // for convenience when debugging locally
      arguments += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
    }

    def agentShadowJar = System.getProperty("datadog.smoketest.agent.shadowJar.path")
    def agentArgument = "-javaagent:${agentShadowJar}=" +
      // for convenience when debugging locally
      (System.getenv("DD_CIVISIBILITY_SMOKETEST_DEBUG_CHILD") != null ? "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT)}=5055," : "") +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.TRACE_DEBUG)}=true," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.ENV)}=${TEST_ENVIRONMENT_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED)}=false," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED)}=false," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL)}=${mockBackend.intakeUrl}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED)}=false,"

    agentArgument += additionalAgentArgs.join(",")

    arguments += agentArgument.toString()
    return arguments.join("\\ ")
  }

  private static String buildJavaHome() {
    if (Jvm.current.isJava8()) {
      return System.getenv("JAVA_8_HOME")
    }
    return System.getenv("JAVA_" + Jvm.current.getJavaSpecificationVersion() + "_HOME")
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
