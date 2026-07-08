package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.civisibility.CiVisibilitySmokeTest;
import datadog.trace.civisibility.CiVisibilityTableTestConverters;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.maven.wrapper.MavenWrapperMain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@DisabledIf(
    value = "disabledOnIbm8",
    disabledReason = "IBM8 has flaky AES-GCM TLS failures when downloading Maven artifacts")
@TypeConverterSources(CiVisibilityTableTestConverters.class)
class MavenSmokeTest extends CiVisibilitySmokeTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenSmokeTest.class);

  private static final String LATEST_MAVEN_VERSION = getLatestMavenVersion();

  private static final String TEST_SERVICE_NAME = "test-maven-service";

  private static final int DEPENDENCIES_DOWNLOAD_TIMEOUT_SECS = 120;
  private static final int PROCESS_TIMEOUT_SECS = 60;
  private static final int DEPENDENCIES_DOWNLOAD_RETRIES = 5;

  public static boolean disabledOnIbm8() {
    return JavaVirtualMachine.isIbm8();
  }

  @TempDir Path projectHome;

  static final MockBackend mockBackend = new MockBackend();

  @BeforeEach
  void resetMockBackend() {
    assumeFalse(JavaVirtualMachine.isJavaVersion(27), "JDK 27 TODO: address failing test");
    mockBackend.reset();
  }

  @AfterAll
  static void closeMockBackend() throws Exception {
    mockBackend.close();
  }

  @TableTest({
    "scenario                   | projectName                                       | mavenVersion                 | expectedEvents | expectedCoverages | expectSuccess | testsSkipping | flakyRetries | jacocoCoverage | commandLineParams                                                | minSupportedJavaVersion",
    "succeed-base               | test_successful_maven_run                         | {3.5.4, 3.6.3, 3.8.8, 3.9.9} | 5              | 1                 | true          | true          | false        | true           | []                                                               | 8                      ",
    "succeed-surefire-3.0.0-j8  | test_successful_maven_run_surefire_3_0_0          | 3.9.9                        | 5              | 1                 | true          | true          | false        | true           | []                                                               | 8                      ",
    "succeed-surefire-3.0.0-j17 | test_successful_maven_run_surefire_3_0_0          | latest                       | 5              | 1                 | true          | true          | false        | true           | []                                                               | 17                     ",
    "succeed-surefire-3.5.0-j8  | test_successful_maven_run_surefire_3_5_0          | 3.9.9                        | 5              | 1                 | true          | true          | false        | true           | []                                                               | 8                      ",
    "succeed-surefire-3.5.0-j17 | test_successful_maven_run_surefire_3_5_0          | latest                       | 5              | 1                 | true          | true          | false        | true           | []                                                               | 17                     ",
    "succeed-builtin-coverage   | test_successful_maven_run_builtin_coverage        | 3.9.9                        | 5              | 1                 | true          | true          | false        | false          | []                                                               | 8                      ",
    "succeed-jacoco-argline     | test_successful_maven_run_with_jacoco_and_argline | 3.9.9                        | 5              | 1                 | true          | true          | false        | true           | []                                                               | 8                      ",
    "succeed-cucumber           | test_successful_maven_run_with_cucumber           | 3.9.9                        | 4              | 1                 | true          | false         | false        | true           | []                                                               | 8                      ",
    "failed-flaky-retries       | test_failed_maven_run_flaky_retries               | 3.9.9                        | 8              | 5                 | false         | false         | true         | true           | []                                                               | 8                      ",
    "succeed-junit-platform     | test_successful_maven_run_junit_platform_runner   | 3.9.9                        | 4              | 0                 | true          | false         | false        | false          | []                                                               | 8                      ",
    "succeed-arg-line-property  | test_successful_maven_run_with_arg_line_property  | 3.9.9                        | 4              | 0                 | true          | false         | false        | false          | [\"-DargLine='-Dmy-custom-property=provided-via-command-line'\"] | 8                      ",
    "succeed-multi-forks-j8     | test_successful_maven_run_multiple_forks          | 3.9.9                        | 5              | 1                 | true          | true          | false        | true           | []                                                               | 8                      ",
    "succeed-multi-forks-j17    | test_successful_maven_run_multiple_forks          | latest                       | 5              | 1                 | true          | true          | false        | true           | []                                                               | 17                     "
  })
  @ParameterizedTest
  void testMavenRun(
      String projectName,
      String mavenVersion,
      int expectedEvents,
      int expectedCoverages,
      boolean expectSuccess,
      boolean testsSkipping,
      boolean flakyRetries,
      boolean jacocoCoverage,
      List<String> commandLineParams,
      int minSupportedJavaVersion)
      throws Exception {
    mavenVersion = resolveLatestMaven(mavenVersion);
    System.out.println("Starting: " + projectName + " " + mavenVersion);
    assumeTrue(
        JavaVirtualMachine.isJavaVersionAtLeast(minSupportedJavaVersion),
        "Current JVM is not compatible with minimum required version " + minSupportedJavaVersion);

    givenWrapperPropertiesFile(mavenVersion);
    givenMavenProjectFiles(projectName);
    givenMavenDependenciesAreLoaded(projectName, mavenVersion);

    mockBackend.givenFlakyRetries(flakyRetries);
    mockBackend.givenFlakyTest(
        "Maven Smoke Tests Project maven-surefire-plugin default-test",
        "datadog.smoke.TestFailed",
        "test_failed");

    mockBackend.givenTestsSkipping(testsSkipping);
    mockBackend.givenSkippableTest(
        "Maven Smoke Tests Project maven-surefire-plugin default-test",
        "datadog.smoke.TestSucceed",
        "test_to_skip_with_itr",
        Collections.singletonMap("src/main/java/datadog/smoke/Calculator.java", bits(9)));

    mockBackend.givenImpactedTestsDetection(true);

    boolean coverageReportExpected =
        jacocoCoverage
            && CiVisibilitySmokeTest.class
                    .getClassLoader()
                    .getResource(projectName + "/coverage_report_event.ftl")
                != null;
    if (coverageReportExpected) {
      mockBackend.givenCodeCoverageReportUpload(true);
    }

    Map<String, String> agentArgs =
        jacocoCoverage
            ? Collections.singletonMap(
                CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION, JACOCO_PLUGIN_VERSION)
            : Collections.emptyMap();
    int exitCode =
        whenRunningMavenBuild(agentArgs, commandLineParams, Collections.emptyMap(), true);

    if (expectSuccess) {
      assertEquals(0, exitCode);
    } else {
      assertNotEquals(0, exitCode);
    }

    verifyEventsAndCoverages(
        projectName,
        "maven",
        mavenVersion,
        mockBackend.waitForEvents(expectedEvents),
        mockBackend.waitForCoverages(expectedCoverages));
    verifyTelemetryMetrics(
        mockBackend.getAllReceivedTelemetryMetrics(),
        mockBackend.getAllReceivedTelemetryDistributions(),
        expectedEvents);

    if (coverageReportExpected) {
      List<datadog.trace.civisibility.CiVisibilityTestUtils.CoverageReport> reports =
          mockBackend.waitForCoverageReports(1);
      String realProjectHome = projectHome.toRealPath().toString();
      verifyCoverageReports(
          projectName, reports, Collections.singletonMap("ci_workspace_path", realProjectHome));
    }
  }

  @TableTest({
    "scenario        | projectName                               | mavenVersion",
    "test-management | test_successful_maven_run_test_management | 3.9.9       "
  })
  @ParameterizedTest
  void testTestManagement(String projectName, String mavenVersion) throws Exception {
    givenWrapperPropertiesFile(mavenVersion);
    givenMavenProjectFiles(projectName);
    givenMavenDependenciesAreLoaded(projectName, mavenVersion);

    mockBackend.givenTestManagement(true);
    mockBackend.givenAttemptToFixRetries(5);

    mockBackend.givenQuarantinedTests(
        "Maven Smoke Tests Project maven-surefire-plugin default-test",
        "datadog.smoke.TestFailed",
        "test_failed");

    mockBackend.givenDisabledTests(
        "Maven Smoke Tests Project maven-surefire-plugin default-test",
        "datadog.smoke.TestSucceeded",
        "test_succeeded");

    mockBackend.givenAttemptToFixTests(
        "Maven Smoke Tests Project maven-surefire-plugin default-test",
        "datadog.smoke.TestSucceeded",
        "test_another_succeeded");

    int exitCode =
        whenRunningMavenBuild(
            Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(), true);
    assertEquals(0, exitCode);

    verifyEventsAndCoverages(
        projectName,
        "maven",
        mavenVersion,
        mockBackend.waitForEvents(11),
        mockBackend.waitForCoverages(3));
  }

  @TableTest({
    "scenario                         | projectName                                              | mavenVersion | surefireVersion       | flakyTests                                                                                                                                | knownTests                                                                                                                                | expectedOrder                                                                                                                                                                                                                                                                              | eventsNumber",
    "junit4-provider                  | test_successful_maven_run_junit4_class_ordering          | 3.9.9        | 3.0.0                 | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          ",
    "junit47-provider                 | test_successful_maven_run_junit4_class_ordering_parallel | 3.9.9        | 3.0.0                 | ['datadog.smoke.TestSucceedC:test_succeed']                                                                                               | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedA:test_succeed']                                                    | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedA:test_succeed']                                                                                                                                                          | 12          ",
    "junit4-provider-latest-surefire  | test_successful_maven_run_junit4_class_ordering          | 3.9.9        | latest-maven-surefire | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          ",
    "junit47-provider-latest-surefire | test_successful_maven_run_junit4_class_ordering_parallel | 3.9.9        | latest-maven-surefire | ['datadog.smoke.TestSucceedC:test_succeed']                                                                                               | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedA:test_succeed']                                                    | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedA:test_succeed']                                                                                                                                                          | 12          "
  })
  @ParameterizedTest
  void testJunit4ClassOrdering(
      String projectName,
      String mavenVersion,
      String surefireVersion,
      List<TestFQN> flakyTests,
      List<TestFQN> knownTests,
      List<TestFQN> expectedOrder,
      int eventsNumber)
      throws Exception {
    surefireVersion =
        "latest-maven-surefire".equals(surefireVersion)
            ? getLatestMavenSurefireVersion()
            : surefireVersion;
    Map<String, String> additionalEnvVars = new HashMap<>();
    additionalEnvVars.put("SMOKE_TEST_SUREFIRE_VERSION", surefireVersion);

    givenWrapperPropertiesFile(mavenVersion);
    givenMavenProjectFiles(projectName);
    givenMavenDependenciesAreLoaded(projectName, mavenVersion, additionalEnvVars);

    for (TestFQN flakyTest : flakyTests) {
      mockBackend.givenFlakyTest(
          "Maven Smoke Tests Project maven-surefire-plugin default-test",
          flakyTest.getSuite(),
          flakyTest.getName());
    }

    mockBackend.givenKnownTests(true);
    for (TestFQN knownTest : knownTests) {
      mockBackend.givenKnownTest(
          "Maven Smoke Tests Project maven-surefire-plugin default-test",
          knownTest.getSuite(),
          knownTest.getName());
    }

    int exitCode =
        whenRunningMavenBuild(
            Collections.singletonMap(
                CiVisibilityConfig.CIVISIBILITY_TEST_ORDER, CIConstants.FAIL_FAST_TEST_ORDER),
            Collections.emptyList(),
            additionalEnvVars,
            true);
    assertEquals(0, exitCode);

    verifyTestOrder(mockBackend.waitForEvents(eventsNumber), expectedOrder);
  }

  @TableTest({
    "scenario                  | projectName                                         | mavenVersion",
    "child-service-propagation | test_successful_maven_run_child_service_propagation | 3.9.9       "
  })
  @ParameterizedTest
  void testServiceNamePropagation(String projectName, String mavenVersion) throws Exception {
    givenWrapperPropertiesFile(mavenVersion);
    givenMavenProjectFiles(projectName);
    givenMavenDependenciesAreLoaded(projectName, mavenVersion);

    int exitCode =
        whenRunningMavenBuild(
            Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(), false);
    assertEquals(0, exitCode);

    List<String> additionalDynamicPaths = Collections.singletonList("content.service");
    verifyEventsAndCoverages(
        projectName,
        "maven",
        mavenVersion,
        mockBackend.waitForEvents(5),
        mockBackend.waitForCoverages(1),
        additionalDynamicPaths);
  }

  @TableTest({
    "scenario           | projectName                          | mavenVersion",
    "failed-test-replay | test_failed_maven_failed_test_replay | 3.9.9       "
  })
  @ParameterizedTest
  void testFailedTestReplay(String projectName, String mavenVersion) throws Exception {
    givenWrapperPropertiesFile(mavenVersion);
    givenMavenProjectFiles(projectName);
    givenMavenDependenciesAreLoaded(projectName, mavenVersion);

    mockBackend.givenFlakyRetries(true);
    mockBackend.givenFlakyTest(
        "Maven Smoke Tests Project maven-surefire-plugin default-test",
        "com.example.TestFailed",
        "test_failed");
    mockBackend.givenFailedTestReplay(true);

    Map<String, String> agentArgs = new HashMap<>();
    agentArgs.put(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_COUNT, "3");
    agentArgs.put(GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL, mockBackend.getIntakeUrl());

    int exitCode =
        whenRunningMavenBuild(agentArgs, Collections.emptyList(), Collections.emptyMap(), true);
    assertEquals(1, exitCode);

    List<String> additionalDynamicTags =
        Arrays.asList(
            "content.meta.['_dd.debug.error.3.snapshot_id']",
            "content.meta.['_dd.debug.error.exception_id']");
    verifyEventsAndCoverages(
        projectName,
        "maven",
        mavenVersion,
        mockBackend.waitForEvents(7),
        mockBackend.waitForCoverages(0),
        additionalDynamicTags);
    verifySnapshots(mockBackend.waitForLogs(2), 2);
  }

  private void givenWrapperPropertiesFile(String mavenVersion) throws IOException {
    String distributionUrl =
        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/"
            + mavenVersion
            + "/apache-maven-"
            + mavenVersion
            + "-bin.zip";

    Properties properties = new Properties();
    properties.setProperty("distributionUrl", distributionUrl);

    Path propertiesFile = projectHome.resolve("maven/wrapper/maven-wrapper.properties");
    Files.createDirectories(propertiesFile.getParent());
    try (FileOutputStream fos = new FileOutputStream(propertiesFile.toFile())) {
      properties.store(fos, "");
    }
  }

  private void givenMavenProjectFiles(String projectFilesSources) throws Exception {
    Path projectResourcesPath =
        Paths.get(this.getClass().getClassLoader().getResource(projectFilesSources).toURI());
    copyFolder(projectResourcesPath, projectHome);

    Path sharedSettingsPath =
        Paths.get(this.getClass().getClassLoader().getResource("settings.mirror.xml").toURI());
    Files.copy(sharedSettingsPath, projectHome.resolve("settings.mirror.xml"));
  }

  private void copyFolder(Path src, Path dest) throws IOException {
    Files.walkFileTree(
        src,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dest.resolve(src.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, dest.resolve(src.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });

    // creating empty .git directory so that the tracer could detect projectFolder as repo root
    Files.createDirectory(projectHome.resolve(".git"));
  }

  /**
   * Sometimes Maven has problems downloading project dependencies because of intermittent network
   * issues. Here, in order to reduce flakiness, we ensure that all of the dependencies are loaded
   * (retrying if necessary), before proceeding with running the build.
   */
  private void givenMavenDependenciesAreLoaded(String projectName, String mavenVersion)
      throws Exception {
    givenMavenDependenciesAreLoaded(projectName, mavenVersion, Collections.emptyMap());
  }

  private void givenMavenDependenciesAreLoaded(
      String projectName, String mavenVersion, Map<String, String> additionalEnvVars)
      throws Exception {
    if (LOADED_DEPENDENCIES.add(projectName + ":" + mavenVersion)) {
      retryUntilSuccessfulOrNoAttemptsLeft(
          Collections.singletonList(
              "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline"),
          additionalEnvVars);
    }
    // Dependencies below are downloaded separately because they are not declared in the project,
    // but are added at runtime by the tracer.
    if (LOADED_DEPENDENCIES.add("com.datadoghq:dd-javac-plugin:" + JAVAC_PLUGIN_VERSION)) {
      retryUntilSuccessfulOrNoAttemptsLeft(
          Arrays.asList(
              "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get",
              "-Dartifact=com.datadoghq:dd-javac-plugin:" + JAVAC_PLUGIN_VERSION),
          additionalEnvVars);
    }
    if (LOADED_DEPENDENCIES.add("org.jacoco:jacoco-maven-plugin:" + JACOCO_PLUGIN_VERSION)) {
      retryUntilSuccessfulOrNoAttemptsLeft(
          Arrays.asList(
              "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get",
              "-Dartifact=org.jacoco:jacoco-maven-plugin:" + JACOCO_PLUGIN_VERSION),
          additionalEnvVars);
    }
  }

  private static final Collection<String> LOADED_DEPENDENCIES = new HashSet<>();

  private void retryUntilSuccessfulOrNoAttemptsLeft(
      List<String> mvnCommand, Map<String, String> additionalEnvVars) throws Exception {
    ProcessBuilder processBuilder =
        createProcessBuilder(mvnCommand, false, false, Collections.emptyMap(), additionalEnvVars);
    for (int attempt = 0; attempt < DEPENDENCIES_DOWNLOAD_RETRIES; attempt++) {
      try {
        int exitCode = runProcess(processBuilder.start(), DEPENDENCIES_DOWNLOAD_TIMEOUT_SECS);
        if (exitCode == 0) {
          return;
        }
      } catch (TimeoutException e) {
        LOGGER.warn("Failed dependency resolution with exception: ", e);
      }
    }
    throw new AssertionError(
        "Tried "
            + DEPENDENCIES_DOWNLOAD_RETRIES
            + " times to execute "
            + mvnCommand
            + " and failed");
  }

  private int whenRunningMavenBuild(
      Map<String, String> additionalAgentArgs,
      List<String> additionalCommandLineParams,
      Map<String, String> additionalEnvVars,
      boolean setServiceName)
      throws Exception {
    List<String> mvnCommand = new ArrayList<>();
    mvnCommand.add("-B");
    mvnCommand.add("test");
    mvnCommand.addAll(additionalCommandLineParams);

    ProcessBuilder processBuilder =
        createProcessBuilder(
            mvnCommand, true, setServiceName, additionalAgentArgs, additionalEnvVars);

    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF");

    return runProcess(processBuilder.start(), PROCESS_TIMEOUT_SECS);
  }

  private static int runProcess(Process p, int timeoutSecs) throws Exception {
    StreamConsumer errorGobbler = new StreamConsumer(p.getErrorStream(), "ERROR");
    StreamConsumer outputGobbler = new StreamConsumer(p.getInputStream(), "OUTPUT");
    outputGobbler.start();
    errorGobbler.start();

    if (!p.waitFor(timeoutSecs, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new TimeoutException(
          "Instrumented process failed to exit within " + timeoutSecs + " seconds");
    }

    return p.exitValue();
  }

  ProcessBuilder createProcessBuilder(
      List<String> mvnCommand,
      boolean runWithAgent,
      boolean setServiceName,
      Map<String, String> additionalAgentArgs,
      Map<String, String> additionalEnvVars) {
    String mavenRunnerShadowJar = System.getProperty("datadog.smoketest.maven.jar.path");
    assertTrue(new File(mavenRunnerShadowJar).isFile());

    List<String> command = new ArrayList<>();
    command.add(javaPath());
    command.addAll(jvmArguments(runWithAgent, setServiceName, additionalAgentArgs));
    command.addAll(Arrays.asList("-jar", mavenRunnerShadowJar));
    command.addAll(programArguments());

    if (System.getenv().get("MAVEN_REPOSITORY_PROXY") != null) {
      command.addAll(Arrays.asList("-s", projectHome.toAbsolutePath() + "/settings.mirror.xml"));
    }
    command.addAll(mvnCommand);

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(projectHome.toFile());

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    for (Map.Entry<String, String> envVar : additionalEnvVars.entrySet()) {
      processBuilder.environment().put(envVar.getKey(), envVar.getValue());
    }

    String mavenRepositoryProxy = System.getenv("MAVEN_REPOSITORY_PROXY");
    if (mavenRepositoryProxy != null) {
      processBuilder.environment().put("MAVEN_REPOSITORY_PROXY", mavenRepositoryProxy);
    }

    return processBuilder;
  }

  List<String> jvmArguments(
      boolean runWithAgent, boolean setServiceName, Map<String, String> additionalAgentArgs) {
    List<String> arguments = new ArrayList<>();
    arguments.add("-D" + MavenWrapperMain.MVNW_VERBOSE + "=true");
    arguments.add("-Duser.dir=" + projectHome.toAbsolutePath());
    arguments.add("-Dmaven.mainClass=org.apache.maven.cli.MavenCli");
    arguments.add("-Dmaven.multiModuleProjectDirectory=" + projectHome.toAbsolutePath());
    arguments.add("-Dmaven.artifact.threads=10");
    if (runWithAgent) {
      arguments.addAll(
          buildJvmArguments(
              mockBackend.getIntakeUrl(),
              setServiceName ? TEST_SERVICE_NAME : null,
              additionalAgentArgs));
    }
    return arguments;
  }

  List<String> programArguments() {
    return Collections.singletonList(projectHome.toAbsolutePath().toString());
  }

  private static class StreamConsumer extends Thread {
    final InputStream is;
    final String messagePrefix;

    StreamConsumer(InputStream is, String messagePrefix) {
      this.is = is;
      this.messagePrefix = messagePrefix;
    }

    @Override
    public void run() {
      try {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
          // DEBUG: logback.xml keeps this logger at INFO, so subprocess output — which may contain
          // secrets (env vars, agent args) — never reaches JUnit XML reports.
          LOGGER.debug("{}: {}", messagePrefix, line);
        }
      } catch (IOException e) {
        LOGGER.warn("Error reading process stream", e);
      }
    }
  }

  private static Properties loadLatestToolVersions() {
    Properties properties = new Properties();
    try (InputStream stream =
        MavenSmokeTest.class
            .getClassLoader()
            .getResourceAsStream("latest-tool-versions.properties")) {
      if (stream == null) {
        throw new IllegalStateException(
            "Could not find latest-tool-versions.properties on classpath");
      }
      properties.load(stream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return properties;
  }

  private static String getLatestMavenVersion() {
    String version = loadLatestToolVersions().getProperty("maven.latest");
    LOGGER.info("Will run the 'latest' tests with Maven version {}", version);
    return version;
  }

  private static String getLatestMavenSurefireVersion() {
    String version = loadLatestToolVersions().getProperty("maven-surefire.latest");
    LOGGER.info("Will run the 'latest' tests with Maven Surefire version {}", version);
    return version;
  }

  private static BitSet bits(int... indices) {
    BitSet bitSet = new BitSet();
    for (int i : indices) {
      bitSet.set(i);
    }
    return bitSet;
  }

  private static String resolveLatestMaven(String mavenVersion) {
    return "latest".equals(mavenVersion) ? LATEST_MAVEN_VERSION : mavenVersion;
  }
}
