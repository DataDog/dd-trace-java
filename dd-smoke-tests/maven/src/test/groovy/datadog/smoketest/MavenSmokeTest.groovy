package datadog.smoketest

import datadog.trace.api.Config
import datadog.trace.api.civisibility.CIConstants
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
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.maven.wrapper.MavenWrapperMain
import org.junit.jupiter.api.Assumptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.TempDir
import spock.util.environment.Jvm

class MavenSmokeTest extends CiVisibilitySmokeTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenSmokeTest.class)

  private static final String LATEST_MAVEN_VERSION = getLatestMavenVersion()

  private static final String TEST_SERVICE_NAME = "test-maven-service"
  private static final String TEST_ENVIRONMENT_NAME = "integration-test"
  private static final String JAVAC_PLUGIN_VERSION = Config.get().ciVisibilityCompilerPluginVersion
  private static final String JACOCO_PLUGIN_VERSION = Config.get().ciVisibilityJacocoPluginVersion

  private static final int PROCESS_TIMEOUT_SECS = 60

  private static final int DEPENDENCIES_DOWNLOAD_RETRIES = 5

  @TempDir
  Path projectHome

  @Shared
  @AutoCleanup
  MockBackend mockBackend = new MockBackend()

  def setup() {
    mockBackend.reset()
  }

  def "test #projectName, v#mavenVersion"() {
    Assumptions.assumeTrue(Jvm.current.isJavaVersionCompatible(minSupportedJavaVersion),
      "Current JVM " + Jvm.current.javaVersion + " is not compatible with minimum required version " + minSupportedJavaVersion)

    givenWrapperPropertiesFile(mavenVersion)
    givenMavenProjectFiles(projectName)
    givenMavenDependenciesAreLoaded(projectName, mavenVersion)

    mockBackend.givenFlakyRetries(flakyRetries)
    mockBackend.givenFlakyTest("Maven Smoke Tests Project maven-surefire-plugin default-test", "datadog.smoke.TestFailed", "test_failed")

    mockBackend.givenTestsSkipping(testsSkipping)
    mockBackend.givenSkippableTest("Maven Smoke Tests Project maven-surefire-plugin default-test", "datadog.smoke.TestSucceed", "test_to_skip_with_itr", ["src/main/java/datadog/smoke/Calculator.java": bits(9)])

    mockBackend.givenImpactedTestsDetection(true)

    def agentArgs = jacocoCoverage ? [
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION)}=${JACOCO_PLUGIN_VERSION}" as String
    ] : []
    def exitCode = whenRunningMavenBuild(agentArgs, commandLineParams)

    if (expectSuccess) {
      assert exitCode == 0
    } else {
      assert exitCode != 0
    }

    verifyEventsAndCoverages(projectName, "maven", mavenVersion, mockBackend.waitForEvents(expectedEvents), mockBackend.waitForCoverages(expectedCoverages))
    verifyTelemetryMetrics(mockBackend.getAllReceivedTelemetryMetrics(), mockBackend.getAllReceivedTelemetryDistributions(), expectedEvents)

    where:
    projectName                                         | mavenVersion         | expectedEvents | expectedCoverages | expectSuccess | testsSkipping | flakyRetries | jacocoCoverage | commandLineParams                                              | minSupportedJavaVersion
    "test_successful_maven_run"                         | "3.5.4"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run"                         | "3.6.3"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run"                         | "3.8.8"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run"                         | "3.9.9"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run_surefire_3_0_0"          | "3.9.9"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run_surefire_3_0_0"          | LATEST_MAVEN_VERSION | 5              | 1                 | true          | true          | false        | true           | []                                                             | 17
    "test_successful_maven_run_surefire_3_5_0"          | "3.9.9"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run_surefire_3_5_0"          | LATEST_MAVEN_VERSION | 5              | 1                 | true          | true          | false        | true           | []                                                             | 17
    "test_successful_maven_run_builtin_coverage"        | "3.9.9"              | 5              | 1                 | true          | true          | false        | false          | []                                                             | 8
    "test_successful_maven_run_with_jacoco_and_argline" | "3.9.9"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    // "expectedEvents" count for this test case does not include the spans that correspond to Cucumber steps
    "test_successful_maven_run_with_cucumber"           | "3.9.9"              | 4              | 1                 | true          | false         | false        | true           | []                                                             | 8
    "test_failed_maven_run_flaky_retries"               | "3.9.9"              | 8              | 5                 | false         | false         | true         | true           | []                                                             | 8
    "test_successful_maven_run_junit_platform_runner"   | "3.9.9"              | 4              | 0                 | true          | false         | false        | false          | []                                                             | 8
    "test_successful_maven_run_with_arg_line_property"  | "3.9.9"              | 4              | 0                 | true          | false         | false        | false          | ["-DargLine='-Dmy-custom-property=provided-via-command-line'"] | 8
    "test_successful_maven_run_multiple_forks"          | "3.9.9"              | 5              | 1                 | true          | true          | false        | true           | []                                                             | 8
    "test_successful_maven_run_multiple_forks"          | LATEST_MAVEN_VERSION | 5              | 1                 | true          | true          | false        | true           | []                                                             | 17
  }

  def "test impacted tests detection"() {
    givenWrapperPropertiesFile(mavenVersion)
    givenMavenProjectFiles(projectName)
    givenMavenDependenciesAreLoaded(projectName, mavenVersion)

    mockBackend.givenImpactedTestsDetection(true)
    mockBackend.givenChangedFile("src/test/java/datadog/smoke/TestSucceed.java")

    def exitCode = whenRunningMavenBuild([
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_GIT_CLIENT_ENABLED)}=false" as String,
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_IMPACTED_TESTS_BACKEND_REQUEST_ENABLED)}=true" as String
    ], [])
    assert exitCode == 0

    verifyEventsAndCoverages(projectName, "maven", mavenVersion, mockBackend.waitForEvents(5), mockBackend.waitForCoverages(1))

    where:
    projectName                                | mavenVersion
    "test_successful_maven_run_impacted_tests" | "3.9.9"
  }

  def "test test management"() {
    givenWrapperPropertiesFile(mavenVersion)
    givenMavenProjectFiles(projectName)
    givenMavenDependenciesAreLoaded(projectName, mavenVersion)

    mockBackend.givenTestManagement(true)
    mockBackend.givenAttemptToFixRetries(5)

    mockBackend.givenQuarantinedTests("Maven Smoke Tests Project maven-surefire-plugin default-test", "datadog.smoke.TestFailed", "test_failed")
    mockBackend.givenQuarantinedTests("Maven Smoke Tests Project maven-surefire-plugin default-test", "datadog.smoke.TestFailed", "test_another_failed")

    mockBackend.givenDisabledTests("Maven Smoke Tests Project maven-surefire-plugin default-test", "datadog.smoke.TestSucceeded", "test_succeed")

    mockBackend.givenAttemptToFixTests("Maven Smoke Tests Project maven-surefire-plugin default-test", "datadog.smoke.TestFailed", "test_another_failed")

    def exitCode = whenRunningMavenBuild([], [])
    assert exitCode == 0

    verifyEventsAndCoverages(projectName, "maven", mavenVersion, mockBackend.waitForEvents(15), mockBackend.waitForCoverages(6))

    where:
    projectName                                 | mavenVersion
    "test_successful_maven_run_test_management" | "3.9.9"
  }

  def "test junit4 class ordering #testcaseName"() {
    def additionalEnvVars = ["SMOKE_TEST_SUREFIRE_VERSION": surefireVersion]

    givenWrapperPropertiesFile(mavenVersion)
    givenMavenProjectFiles(projectName)
    givenMavenDependenciesAreLoaded(projectName, mavenVersion, additionalEnvVars)

    for (flakyTest in flakyTests) {
      mockBackend.givenFlakyTest("Maven Smoke Tests Project maven-surefire-plugin default-test", flakyTest.getSuite(), flakyTest.getName())
    }

    mockBackend.givenKnownTests(true)
    for (knownTest in knownTests) {
      mockBackend.givenKnownTest("Maven Smoke Tests Project maven-surefire-plugin default-test", knownTest.getSuite(), knownTest.getName())
    }

    def exitCode = whenRunningMavenBuild([
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_TEST_ORDER)}=${CIConstants.FAIL_FAST_TEST_ORDER}" as String
    ], [], additionalEnvVars)
    assert exitCode == 0

    verifyTestOrder(mockBackend.waitForEvents(eventsNumber), expectedOrder)

    where:
    testcaseName                      | projectName                                                | mavenVersion | surefireVersion | flakyTests | knownTests                                                                                               | expectedOrder                                                                                                                                                | eventsNumber
    "junit4-provider"  | "test_successful_maven_run_junit4_class_ordering"          | "3.9.9"      | "3.0.0"         | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ]                                                                                        | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ]                                                                                                                                                                                                   | [
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedC", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another")
    ]                                                                                                                                                                                                                                                                                                                                                          | 15
    "junit47-provider" | "test_successful_maven_run_junit4_class_ordering_parallel" | "3.9.9"      | "3.0.0"         | [test("datadog.smoke.TestSucceedC", "test_succeed")]                                                                                        | [
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ] | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ] | 12
    "junit4-provider-latest-surefire"  | "test_successful_maven_run_junit4_class_ordering"          | "3.9.9"      | getLatestMavenSurefireVersion()         | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ]                                                                                        | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ]                                                                                                                                                                                                   | [
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedC", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another")
    ]                                                                                                                                                                                                                                                                                                                                                          | 15
    "junit47-provider-latest-surefire" | "test_successful_maven_run_junit4_class_ordering_parallel" | "3.9.9"      | getLatestMavenSurefireVersion()         | [test("datadog.smoke.TestSucceedC", "test_succeed")]                                                                                        | [
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ] | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ] | 12
  }

  private void givenWrapperPropertiesFile(String mavenVersion) {
    def distributionUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${mavenVersion}/apache-maven-${mavenVersion}-bin.zip"

    def properties = new Properties()
    properties.setProperty("distributionUrl", distributionUrl)

    def propertiesFile = projectHome.resolve("maven/wrapper/maven-wrapper.properties")
    Files.createDirectories(propertiesFile.getParent())
    new FileOutputStream(propertiesFile.toFile()).withCloseable {
      properties.store(it, "")
    }
  }

  private void givenMavenProjectFiles(String projectFilesSources) {
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

  /**
   * Sometimes Maven has problems downloading project dependencies because of intermittent network issues.
   * Here, in order to reduce flakiness, we ensure that all of the dependencies are loaded (retrying if necessary),
   * before proceeding with running the build
   */
  private void givenMavenDependenciesAreLoaded(String projectName, String mavenVersion, Map<String, String> additionalEnvVars = [:]) {
    if (LOADED_DEPENDENCIES.add("$projectName:$mavenVersion")) {
      retryUntilSuccessfulOrNoAttemptsLeft(["dependency:go-offline"], additionalEnvVars)
    }
    // dependencies below are download separately
    // because they are not declared in the project,
    // but are added at runtime by the tracer
    if (LOADED_DEPENDENCIES.add("com.datadoghq:dd-javac-plugin:$JAVAC_PLUGIN_VERSION")) {
      retryUntilSuccessfulOrNoAttemptsLeft(["dependency:get", "-Dartifact=com.datadoghq:dd-javac-plugin:$JAVAC_PLUGIN_VERSION".toString()], additionalEnvVars)
    }
    if (LOADED_DEPENDENCIES.add("org.jacoco:jacoco-maven-plugin:$JACOCO_PLUGIN_VERSION")) {
      retryUntilSuccessfulOrNoAttemptsLeft(["dependency:get", "-Dartifact=org.jacoco:jacoco-maven-plugin:$JACOCO_PLUGIN_VERSION".toString()], additionalEnvVars)
    }
  }

  private static final Collection<String> LOADED_DEPENDENCIES = new HashSet<>()

  private void retryUntilSuccessfulOrNoAttemptsLeft(List<String> mvnCommand, Map<String, String> additionalEnvVars = [:]) {
    def processBuilder = createProcessBuilder(mvnCommand, false, [], additionalEnvVars)
    for (int attempt = 0; attempt < DEPENDENCIES_DOWNLOAD_RETRIES; attempt++) {
      def exitCode = runProcess(processBuilder.start())
      if (exitCode == 0) {
        return
      }
    }
    throw new AssertionError((Object) "Tried $DEPENDENCIES_DOWNLOAD_RETRIES times to execute $mvnCommand and failed")
  }

  private int whenRunningMavenBuild(List<String> additionalAgentArgs, List<String> additionalCommandLineParams, Map<String, String> additionalEnvVars = [:]) {
    def processBuilder = createProcessBuilder(["-B", "test"] + additionalCommandLineParams, true, additionalAgentArgs, additionalEnvVars)

    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF")

    return runProcess(processBuilder.start())
  }

  private static runProcess(Process p) {
    StreamConsumer errorGobbler = new StreamConsumer(p.getErrorStream(), "ERROR")
    StreamConsumer outputGobbler = new StreamConsumer(p.getInputStream(), "OUTPUT")
    outputGobbler.start()
    errorGobbler.start()

    if (!p.waitFor(PROCESS_TIMEOUT_SECS, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      throw new TimeoutException("Instrumented process failed to exit")
    }

    return p.exitValue()
  }

  ProcessBuilder createProcessBuilder(List<String> mvnCommand, boolean runWithAgent, List<String> additionalAgentArgs, Map<String, String> additionalEnvVars) {
    String mavenRunnerShadowJar = System.getProperty("datadog.smoketest.maven.jar.path")
    assert new File(mavenRunnerShadowJar).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(jvmArguments(runWithAgent, additionalAgentArgs))
    command.addAll((String[]) ["-jar", mavenRunnerShadowJar])
    command.addAll(programArguments())
    command.addAll(mvnCommand)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(projectHome.toFile())

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
    for (envVar in additionalEnvVars) {
      processBuilder.environment().put(envVar.key, envVar.value)
    }

    return processBuilder
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  List<String> jvmArguments(boolean runWithAgent, List<String> additionalAgentArgs) {
    def arguments = [
      "-D${MavenWrapperMain.MVNW_VERBOSE}=true".toString(),
      "-Duser.dir=${projectHome.toAbsolutePath()}".toString(),
      "-Dmaven.multiModuleProjectDirectory=${projectHome.toAbsolutePath()}".toString(),
    ]
    if (runWithAgent) {
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
        "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED)}=false," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED)}=false," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_VERSION)}=${JAVAC_PLUGIN_VERSION}," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL)}=${mockBackend.intakeUrl}," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES)}=true,"

      agentArgument += additionalAgentArgs.join(",")

      arguments += agentArgument.toString()
    }
    return arguments
  }

  List<String> programArguments() {
    return [projectHome.toAbsolutePath().toString()]
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

  private static String getLatestMavenVersion() {
    OkHttpClient client = new OkHttpClient()
    Request request = new Request.Builder().url("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml").build()
    try (Response response = client.newCall(request).execute()) {
      if (response.successful) {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance()
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder()
        Document doc = dBuilder.parse(response.body().byteStream())
        doc.getDocumentElement().normalize()

        NodeList versionList = doc.getElementsByTagName("latest")
        if (versionList.getLength() > 0) {
          def version = versionList.item(0).getTextContent()
          if (!version.contains('alpha') && !version.contains('beta') && !version.contains('rc')) {
            LOGGER.info("Will run the 'latest' tests with version ${version}")
            return version
          }
        }
      } else {
        LOGGER.warn("Could not get latest maven version, response from repo.maven.apache.org is ${response.code()}: ${response.body().string()}")
      }
    } catch (Exception e) {
      LOGGER.warn("Could not get latest maven version", e)
    }
    def hardcodedLatestVersion = "4.0.0-beta-3" // latest version that is known to work
    LOGGER.info("Will run the 'latest' tests with hard-coded version ${hardcodedLatestVersion}")
    return hardcodedLatestVersion
  }

  private static String getLatestMavenSurefireVersion() {
    OkHttpClient client = new OkHttpClient()
    Request request =
    new Request.Builder()
    .url(
    "https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml")
    .build()
    try (Response response = client.newCall(request).execute()) {
      if (response.isSuccessful()) {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance()
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder()
        Document doc = dBuilder.parse(response.body().byteStream())
        doc.getDocumentElement().normalize()

        NodeList versionList = doc.getElementsByTagName("latest")
        if (versionList.getLength() > 0) {
          String version = versionList.item(0).getTextContent()
          if (!version.contains("alpha") && !version.contains("beta")) {
            LOGGER.info("Will run the 'latest' tests with version " + version)
            return version
          }
        }
      } else {
        LOGGER.warn(
        "Could not get latest Maven Surefire version, response from repo.maven.apache.org is "
        + response.code()
        + ":"
        + response.body().string())
      }
    } catch (Exception e) {
      LOGGER.warn("Could not get latest Maven Surefire version", e)
    }
    String hardcodedLatestVersion = "3.5.0" // latest version that is known to work
    LOGGER.info("Will run the 'latest' tests with hard-coded version " + hardcodedLatestVersion)
    return hardcodedLatestVersion
  }

  private static BitSet bits(int ... indices) {
    BitSet bitSet = new BitSet()
    for (int i : indices) {
      bitSet.set(i)
    }
    return bitSet
  }
}
