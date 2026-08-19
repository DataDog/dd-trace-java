package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.civisibility.CiVisibilityTableTestConverters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.tooling.internal.consumer.DefaultGradleConnector;
import org.gradle.util.GradleVersion;
import org.gradle.wrapper.Download;
import org.gradle.wrapper.Install;
import org.gradle.wrapper.PathAssembler;
import org.gradle.wrapper.WrapperConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(CiVisibilityTableTestConverters.class)
class GradleDaemonSmokeTest extends AbstractGradleTest {

  private static final String TEST_SERVICE_NAME = "test-gradle-service";
  private static final Path GRADLE_DAEMON_DIAGNOSTICS_DIR =
      Paths.get(
          System.getProperty("datadog.smoketest.builddir", "build"),
          "reports",
          "gradle-daemon-diagnostics");
  private static final Pattern DAEMON_LOG_PATTERN = Pattern.compile("daemon-(.+)\\.out\\.log");

  // Gradle's default timeout is 10s
  private static final int GRADLE_DISTRIBUTION_NETWORK_TIMEOUT = 30_000;

  // Cleanup is handled manually in stopGradleTestKitDaemons() instead of by JUnit: the TestKit
  // daemons may still hold file handles on this directory at class teardown, which would make
  // JUnit's recursive delete fail and turn the class into an executionError.
  @TempDir(cleanup = CleanupMode.NEVER)
  static Path testKitFolder;

  @AfterAll
  void stopGradleTestKitDaemons() {
    try {
      DefaultGradleConnector.close();
    } catch (Exception e) {
      System.err.println("Failed to stop Gradle TestKit daemons during cleanup: " + e);
    }
    killGradleDaemonsIn(testKitFolder);
    deleteTempDirectoryQuietly(testKitFolder);
  }

  @TableTest({
    "scenario                    | gradleVersion | projectName                                      | successExpected | expectedTraces | expectedCoverages",
    "succeed-old-gradle-oldest   | oldest        | test-succeed-old-gradle                          | true            | 5              | 1                ",
    "succeed-legacy              | 7.6.4         | test-succeed-legacy-instrumentation              | true            | 5              | 1                ",
    "succeed-multi-module-legacy | 7.6.4         | test-succeed-multi-module-legacy-instrumentation | true            | 7              | 2                ",
    "succeed-multi-forks-legacy  | 7.6.4         | test-succeed-multi-forks-legacy-instrumentation  | true            | 6              | 2                ",
    "skip-legacy                 | 7.6.4         | test-skip-legacy-instrumentation                 | true            | 2              | 0                ",
    "failed-legacy               | 7.6.4         | test-failed-legacy-instrumentation               | false           | 4              | 0                ",
    "corrupted-config-legacy     | 7.6.4         | test-corrupted-config-legacy-instrumentation     | false           | 1              | 0                "
  })
  @ParameterizedTest
  void testLegacy(
      String gradleVersion,
      String projectName,
      boolean successExpected,
      int expectedTraces,
      int expectedCoverages)
      throws IOException {
    // Jacoco plugin does not work with OpenJ9 in older Gradle versions
    Assumptions.assumeFalse(
        JavaVirtualMachine.isJ9(),
        "Jacoco plugin does not work with OpenJ9 in older Gradle versions");
    runGradleTest(
        gradleVersion,
        projectName,
        false,
        successExpected,
        false,
        expectedTraces,
        expectedCoverages);
  }

  @TableTest({
    "scenario                   | gradleVersion | projectName                                   | configurationCache | successExpected | flakyRetries | expectedTraces | expectedCoverages",
    "succeed-new-8.3            | 8.3           | test-succeed-new-instrumentation              | {false, true}      | true            | false        | 5              | 1                ",
    "succeed-new-8.9            | 8.9           | test-succeed-new-instrumentation              | {false, true}      | true            | false        | 5              | 1                ",
    "succeed-new-latest         | latest        | test-succeed-new-instrumentation              | {false, true}      | true            | false        | 5              | 1                ",
    "succeed-multi-module-new   | latest        | test-succeed-multi-module-new-instrumentation | false              | true            | false        | 7              | 2                ",
    "succeed-multi-forks-new    | latest        | test-succeed-multi-forks-new-instrumentation  | false              | true            | false        | 6              | 2                ",
    "skip-new                   | latest        | test-skip-new-instrumentation                 | false              | true            | false        | 2              | 0                ",
    "failed-new                 | latest        | test-failed-new-instrumentation               | false              | false           | false        | 4              | 0                ",
    "corrupted-config-new       | latest        | test-corrupted-config-new-instrumentation     | false              | false           | false        | 1              | 0                ",
    "succeed-junit-5            | latest        | test-succeed-junit-5                          | false              | true            | false        | 5              | 1                ",
    "failed-flaky-retries       | latest        | test-failed-flaky-retries                     | false              | false           | true         | 8              | 0                ",
    "succeed-gradle-plugin-test | latest        | test-succeed-gradle-plugin-test               | false              | true            | false        | 5              | 0                "
  })
  @ParameterizedTest
  void testNew(
      String gradleVersion,
      String projectName,
      boolean configurationCache,
      boolean successExpected,
      boolean flakyRetries,
      int expectedTraces,
      int expectedCoverages)
      throws IOException {
    runGradleTest(
        gradleVersion,
        projectName,
        configurationCache,
        successExpected,
        flakyRetries,
        expectedTraces,
        expectedCoverages);
  }

  @TableTest({
    "scenario           | gradleVersion | projectName              | expectedTraces",
    "robolectric-latest | latest        | test-succeed-robolectric | 7             "
  })
  @ParameterizedTest
  void testRobolectric(String gradleVersion, String projectName, int expectedTraces)
      throws IOException {
    Assumptions.assumeTrue(
        JavaVirtualMachine.isJavaVersionBetween(17, 22), "Robolectric 4.16 supports JDK 17-21");
    Assumptions.assumeFalse(
        OperatingSystem.architecture().isArm64(),
        "Robolectric does not support arm64 (missing native runtime binaries, follow https://github.com/robolectric/robolectric/issues/9166)");

    gradleVersion = resolveVersion(gradleVersion);
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion);
    givenGradleVersionIsSupportedByCurrentGradleTestKit(gradleVersion);
    givenGradleProjectFiles(projectName);
    givenGradleProjectProperties(gradleVersion);
    ensureDependenciesDownloaded(gradleVersion);

    BuildResult buildResult = runGradleTests(gradleVersion, true, false);
    assertBuildSuccessful(buildResult);

    verifyEventsAndCoverages(
        projectName,
        "gradle",
        gradleVersion,
        mockBackend.waitForEvents(expectedTraces),
        mockBackend.waitForCoverages(0));
  }

  @TableTest({
    "scenario       | gradleVersion | verificationEnabled",
    "legacy-default | 7.6.4         | false              ",
    "legacy-enabled | 7.6.4         | true               ",
    "modern-default | 8.3           | false              ",
    "modern-enabled | 8.3           | true               "
  })
  @ParameterizedTest
  void testInjectedDependencyVerification(String gradleVersion, boolean verificationEnabled)
      throws IOException {
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion);
    givenGradleVersionIsSupportedByCurrentGradleTestKit(gradleVersion);
    givenGradleProjectFiles("test-gradle-dependency-verification");

    Map<String, String> additionalArgs = new HashMap<>();
    if (verificationEnabled) {
      additionalArgs.put(
          CiVisibilityConfig.CIVISIBILITY_GRADLE_DEPENDENCY_VERIFICATION_ENABLED, "true");
    }
    givenGradleProjectProperties(gradleVersion, additionalArgs);
    ensureDependenciesDownloaded(gradleVersion);

    BuildResult buildResult =
        runGradle(
            gradleVersion, Arrays.asList("compileJava", "--stacktrace"), !verificationEnabled);

    if (verificationEnabled) {
      assertTrue(buildResult.getOutput().contains("Dependency verification failed"));
    } else {
      assertBuildSuccessful(buildResult);
      String warning =
          "Datadog Test Optimization disabled Gradle dependency verification for dependencies "
              + "injected into this build.";
      int firstWarning = buildResult.getOutput().indexOf(warning);
      assertTrue(firstWarning >= 0);
      assertEquals(firstWarning, buildResult.getOutput().lastIndexOf(warning));
    }
  }

  @TableTest({
    "scenario               | gradleVersion | projectName                         | flakyTests                                                                                                                                | expectedOrder                                                                                                                                                                                                                                                                              | eventsNumber",
    "junit4-ordering-7.6.4  | 7.6.4         | test-succeed-junit-4-class-ordering | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          ",
    "junit4-ordering-9.2.1  | 9.2.1         | test-succeed-junit-4-class-ordering | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          ",
    "junit4-ordering-9.3.0  | 9.3.0         | test-succeed-junit-4-class-ordering | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          ",
    "junit4-ordering-latest | latest        | test-succeed-junit-4-class-ordering | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          "
  })
  @ParameterizedTest
  void testJunit4ClassOrdering(
      String gradleVersion,
      String projectName,
      List<TestFQN> flakyTests,
      List<TestFQN> expectedOrder,
      int eventsNumber)
      throws IOException {
    gradleVersion = resolveVersion(gradleVersion);
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion);
    givenGradleProjectFiles(projectName);
    givenGradleProjectProperties(gradleVersion);
    ensureDependenciesDownloaded(gradleVersion);

    mockBackend.givenKnownTests(true);
    for (TestFQN flakyTest : flakyTests) {
      mockBackend.givenFlakyTest(":test", flakyTest.getSuite(), flakyTest.getName());
      mockBackend.givenKnownTest(":test", flakyTest.getSuite(), flakyTest.getName());
    }

    BuildResult buildResult = runGradleTests(gradleVersion, true, false);
    assertBuildSuccessful(buildResult);

    verifyTestOrder(mockBackend.waitForEvents(eventsNumber), expectedOrder);
  }

  // Resolves the symbolic versions used in the scenario tables:
  //  - "latest": the newest eligible Gradle release
  //  - "oldest": the latest patch of the oldest major the current Gradle TestKit still supports
  // Any other value is treated as a concrete version and returned as-is.
  private static String resolveVersion(String gradleVersion) {
    if ("latest".equals(gradleVersion)) {
      return LATEST_GRADLE_VERSION;
    }
    if ("oldest".equals(gradleVersion)) {
      return oldestSupportedGradleVersion();
    }
    return gradleVersion;
  }

  private static String oldestSupportedGradleVersion() {
    // The oldest major the current Gradle TestKit can run is dictated by Gradle itself; tracking it
    // dynamically (rather than hardcoding a version) means the floor follows TestKit automatically.
    // We test the latest patch of that major rather than its initial release for stability.
    int oldestSupportedMajor =
        DefaultGradleConnector.MINIMUM_SUPPORTED_GRADLE_VERSION.getMajorVersion();
    return toolVersion("gradle.latest." + oldestSupportedMajor);
  }

  private static void givenGradleVersionIsSupportedByCurrentGradleTestKit(String gradleVersion) {
    // These smoke tests cover the earliest legacy Gradle version that the current Gradle
    // TestKit can still run. When Gradle raises TestKit's minimum supported version, older
    // rows are skipped as a best-effort compatibility boundary rather than a product signal.
    Assumptions.assumeTrue(
        GradleVersion.version(gradleVersion)
                .compareTo(DefaultGradleConnector.MINIMUM_SUPPORTED_GRADLE_VERSION)
            >= 0,
        "Current Gradle TestKit does not support Gradle version " + gradleVersion);
  }

  private void runGradleTest(
      String gradleVersion,
      String projectName,
      boolean configurationCache,
      boolean successExpected,
      boolean flakyRetries,
      int expectedTraces,
      int expectedCoverages)
      throws IOException {
    gradleVersion = resolveVersion(gradleVersion);
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion);
    givenGradleVersionIsSupportedByCurrentGradleTestKit(gradleVersion);
    givenConfigurationCacheIsCompatibleWithCurrentPlatform(configurationCache);
    givenGradleProjectFiles(projectName);
    givenGradleProjectProperties(gradleVersion);
    ensureDependenciesDownloaded(gradleVersion);

    mockBackend.givenFlakyRetries(flakyRetries);
    mockBackend.givenFlakyTest(":test", "datadog.smoke.TestFailed", "test_failed");

    mockBackend.givenTestsSkipping(true);
    mockBackend.givenSkippableTest(
        ":test", "datadog.smoke.TestSucceed", "test_to_skip_with_itr", Collections.emptyMap());

    BuildResult buildResult = runGradleTests(gradleVersion, successExpected, configurationCache);

    if (successExpected) {
      assertBuildSuccessful(buildResult);
    }

    verifyEventsAndCoverages(
        projectName,
        "gradle",
        gradleVersion,
        mockBackend.waitForEvents(expectedTraces),
        mockBackend.waitForCoverages(expectedCoverages));

    if (configurationCache) {
      // If configuration cache is enabled, run the build one more time to verify that building
      // with an existing configuration cache entry works.
      BuildResult buildResultWithConfigCacheEntry =
          runGradleTests(gradleVersion, successExpected, configurationCache);

      assertBuildSuccessful(buildResultWithConfigCacheEntry);
      verifyEventsAndCoverages(
          projectName,
          "gradle",
          gradleVersion,
          mockBackend.waitForEvents(expectedTraces),
          mockBackend.waitForCoverages(expectedCoverages));
    }
  }

  private void givenGradleProjectProperties(String gradleVersion) throws IOException {
    givenGradleProjectProperties(gradleVersion, Collections.emptyMap());
  }

  private void givenGradleProjectProperties(
      String gradleVersion, Map<String, String> additionalArgs) throws IOException {
    assertTrue(new java.io.File(AGENT_JAR).isFile());

    Path ddApiKeyPath = testKitFolder.resolve(".dd.api.key");
    Files.write(ddApiKeyPath, "dummy".getBytes());

    Map<String, String> effectiveAdditionalArgs = new HashMap<>(additionalArgs);
    effectiveAdditionalArgs.put(
        GeneralConfig.API_KEY_FILE, ddApiKeyPath.toAbsolutePath().toString());
    effectiveAdditionalArgs.put(
        CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION, JACOCO_PLUGIN_VERSION);
    /*
     * Some of the smoke tests (in particular the one with the Gradle plugin), are using Gradle Test Kit for their tests.
     * Gradle Test Kit needs to do a "chmod" when starting a Gradle Daemon.
     * This "chmod" operation is traced by datadog.trace.instrumentation.java.lang.ProcessImplInstrumentation and is reported as a span.
     * The problem is that the "chmod" only happens when running in CI (could be due to differences in OS or FS permissions),
     * so when running the tests locally, the "chmod" span is not there.
     * This causes the tests to fail because the number of reported traces is different.
     * To avoid this discrepancy between local and CI runs, we disable tracing instrumentations.
     */
    effectiveAdditionalArgs.put(TraceInstrumentationConfig.TRACE_ENABLED, "false");
    List<String> arguments =
        buildJvmArguments(mockBackend.getIntakeUrl(), TEST_SERVICE_NAME, effectiveAdditionalArgs);
    // Gradle 4.10.3's native-platform crashes on Linux when restoring non-ASCII environment values.
    if (GradleVersion.version("4.10.3").equals(GradleVersion.version(gradleVersion))) {
      arguments.add("-Dorg.gradle.native=false");
    }
    addCrashDiagnostics(arguments);

    String gradleProperties = "org.gradle.jvmargs=" + String.join(" ", arguments);
    // Write to projectFolder (per-test) instead of testKitFolder (shared), so each
    // Gradle daemon gets its own unique preference directory.
    Files.write(projectFolder.resolve("gradle.properties"), gradleProperties.getBytes());
  }

  private BuildResult runGradleTests(
      String gradleVersion, boolean successExpected, boolean configurationCache)
      throws IOException {
    List<String> arguments = new java.util.ArrayList<>(Arrays.asList("test", "--stacktrace"));
    if (gradleVersion.compareTo("4.5") > 0) {
      // warning mode available starting from Gradle 4.5
      arguments.addAll(Arrays.asList("--warning-mode", "all"));
    }
    if (configurationCache) {
      arguments.addAll(Arrays.asList("--configuration-cache", "--rerun-tasks"));
    }
    return runGradle(gradleVersion, arguments, successExpected);
  }

  /**
   * Sometimes Gradle Test Kit fails because it cannot download the required Gradle distribution due
   * to intermittent network issues. This method performs the download manually (if needed) with
   * increased timeout (30s vs default 10s). Retry logic (3 retries) is already present in {@code
   * org.gradle.wrapper.Install}.
   */
  private void ensureDependenciesDownloaded(String gradleVersion) {
    try {
      org.gradle.wrapper.Logger logger = new org.gradle.wrapper.Logger(false);
      Download download =
          new Download(
              logger,
              "Gradle Tooling API",
              GradleVersion.current().getVersion(),
              GRADLE_DISTRIBUTION_NETWORK_TIMEOUT);

      java.io.File userHomeDir = testKitFolder.toFile();
      java.io.File projectDir = projectFolder.toFile();
      Install install = new Install(logger, download, new PathAssembler(userHomeDir, projectDir));

      WrapperConfiguration configuration = new WrapperConfiguration();
      configuration.setDistribution(GradleDistribution.uriFor(gradleVersion));
      configuration.setNetworkTimeout(GRADLE_DISTRIBUTION_NETWORK_TIMEOUT);

      // This will download distribution (if not downloaded yet to userHomeDir) and verify its SHA.
      install.createDist(configuration);
    } catch (Exception e) {
      System.out.println(
          "Failed to install Gradle distribution, will proceed to run test kit hoping for the best: "
              + e);
    }
  }

  private BuildResult runGradle(
      String gradleVersion, List<String> arguments, boolean successExpected) throws IOException {
    Map<String, String> buildEnv = new HashMap<>();
    buildEnv.put("GRADLE_ARGS", "");
    buildEnv.put("GRADLE_OPTS", "");
    buildEnv.put("GRADLE_USER_HOME", testKitFolder.toString());
    buildEnv.put("GRADLE_VERSION", gradleVersion);
    buildEnv.put(
        GradleDistribution.GRADLE_DISTRIBUTION_URL_ENV,
        GradleDistribution.uriFor(gradleVersion).toString());

    String mavenRepositoryProxy = System.getenv("MAVEN_REPOSITORY_PROXY");
    if (mavenRepositoryProxy != null) {
      buildEnv.put("MAVEN_REPOSITORY_PROXY", mavenRepositoryProxy);
    }
    GradleDistribution.propagateMassReadUrl(buildEnv);

    GradleRunner gradleRunner =
        GradleDistribution.withDistribution(
                GradleRunner.create()
                    .withTestKitDir(testKitFolder.toFile())
                    .withProjectDir(projectFolder.toFile()),
                gradleVersion)
            .withArguments(arguments)
            .withEnvironment(buildEnv)
            .forwardOutput();

    try {
      return successExpected ? gradleRunner.build() : gradleRunner.buildAndFail();
    } catch (Exception e) {
      try {
        collectDaemonDiagnostics(gradleVersion, e);
      } catch (Exception diagnosticsError) {
        System.err.println("Failed to collect Gradle daemon diagnostics: " + diagnosticsError);
      }
      throw new RuntimeException(e);
    }
  }

  private static void addCrashDiagnostics(List<String> arguments) {
    try {
      Files.createDirectories(GRADLE_DAEMON_DIAGNOSTICS_DIR);
      if (JavaVirtualMachine.isJ9()) {
        arguments.add("-Xdump:directory=" + GRADLE_DAEMON_DIAGNOSTICS_DIR.toAbsolutePath());
      } else {
        arguments.add(
            "-XX:ErrorFile="
                + GRADLE_DAEMON_DIAGNOSTICS_DIR.toAbsolutePath()
                + "/hs_err_pid%p.log");
      }
    } catch (IOException e) {
      System.err.println("Failed to configure Gradle daemon crash diagnostics: " + e);
    }
  }

  private void collectDaemonDiagnostics(String gradleVersion, Exception failure)
      throws IOException {
    Path failureDir =
        GRADLE_DAEMON_DIAGNOSTICS_DIR.resolve(
            gradleVersion + "-" + projectFolder.getFileName().toString());
    Files.createDirectories(failureDir);

    List<String> summary = new ArrayList<>();
    Throwable cause = failure;
    int causeIndex = 0;
    while (cause != null) {
      String message = cause.getMessage();
      int lineBreak = message != null ? message.indexOf('\n') : -1;
      if (lineBreak >= 0) {
        message = message.substring(0, lineBreak);
      }
      summary.add("failure[" + causeIndex++ + "]=" + cause.getClass().getName() + ": " + message);
      cause = cause.getCause();
    }
    summary.add("gradleVersion=" + gradleVersion);

    Path cgroupMembership = Paths.get("/proc/self/cgroup");
    Path daemonLogDir = testKitFolder.resolve("test-kit-daemon/" + gradleVersion);
    Path daemonLog = newestDaemonLog(daemonLogDir);
    if (daemonLog != null) {
      copyIfExists(daemonLog, failureDir.resolve(daemonLog.getFileName()), summary);
      summary.add("daemonLog=" + daemonLog);

      Matcher matcher = DAEMON_LOG_PATTERN.matcher(daemonLog.getFileName().toString());
      if (matcher.matches()) {
        String daemonIdentifier = matcher.group(1);
        summary.add("daemonIdentifier=" + daemonIdentifier);
        if (daemonIdentifier.matches("\\d+")) {
          summary.add("daemonPid=" + daemonIdentifier);
          Path procRoot = Paths.get("/proc");
          if (Files.isDirectory(procRoot)) {
            Path processDir = procRoot.resolve(daemonIdentifier);
            summary.add("daemonProcessPresent=" + Files.isDirectory(processDir));
            copyIfExists(processDir.resolve("status"), failureDir.resolve("proc-status"), summary);
            copyIfExists(processDir.resolve("limits"), failureDir.resolve("proc-limits"), summary);
            Path daemonCgroupMembership = processDir.resolve("cgroup");
            copyIfExists(daemonCgroupMembership, failureDir.resolve("proc-cgroup"), summary);
            if (Files.isRegularFile(daemonCgroupMembership)) {
              cgroupMembership = daemonCgroupMembership;
            }
          } else {
            summary.add("daemonProcessPresent=unknown");
          }
        }
      }

      try {
        System.out.println("==============================================================");
        System.out.println(
            "Gradle Daemon log:\n"
                + new String(Files.readAllBytes(daemonLog), StandardCharsets.UTF_8));
        System.out.println("==============================================================");
      } catch (IOException e) {
        summary.add("daemonLogReadFailed=" + e);
      }
    } else {
      summary.add("daemonLog=not found");
    }

    summary.add("cgroupMembership=" + cgroupMembership);
    Path cgroupDirectory = resolveCgroupV2Directory(cgroupMembership, summary);
    summary.add("cgroupDirectory=" + cgroupDirectory);
    copyIfExists(
        cgroupDirectory.resolve("memory.events"),
        failureDir.resolve("cgroup-memory.events"),
        summary);
    copyIfExists(
        cgroupDirectory.resolve("memory.current"),
        failureDir.resolve("cgroup-memory.current"),
        summary);
    copyIfExists(
        cgroupDirectory.resolve("memory.peak"), failureDir.resolve("cgroup-memory.peak"), summary);
    copyIfExists(
        cgroupDirectory.resolve("pids.events"), failureDir.resolve("cgroup-pids.events"), summary);
    copyIfExists(
        cgroupDirectory.resolve("pids.current"),
        failureDir.resolve("cgroup-pids.current"),
        summary);
    copyIfExists(
        cgroupDirectory.resolve("pids.max"), failureDir.resolve("cgroup-pids.max"), summary);

    Files.write(failureDir.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
    System.out.println("Gradle daemon diagnostics written to " + failureDir);
  }

  private static Path resolveCgroupV2Directory(Path cgroupMembership, List<String> summary) {
    Path cgroupRoot = Paths.get("/sys/fs/cgroup");
    if (!Files.isRegularFile(cgroupMembership)) {
      return cgroupRoot;
    }

    try {
      for (String line : Files.readAllLines(cgroupMembership, StandardCharsets.UTF_8)) {
        if (line.startsWith("0::")) {
          String relativePath = line.substring(3);
          if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
          }
          Path cgroupDirectory = cgroupRoot.resolve(relativePath);
          if (Files.isDirectory(cgroupDirectory)) {
            return cgroupDirectory;
          }
          summary.add("cgroupDirectoryNotFound=" + cgroupDirectory);
          break;
        }
      }
    } catch (IOException e) {
      summary.add("cgroupMembershipReadFailed[" + cgroupMembership + "]=" + e);
    }
    return cgroupRoot;
  }

  private static Path newestDaemonLog(Path daemonLogDir) throws IOException {
    if (!Files.isDirectory(daemonLogDir)) {
      return null;
    }
    try (Stream<Path> daemonLogs = Files.list(daemonLogDir)) {
      return daemonLogs
          .filter(path -> DAEMON_LOG_PATTERN.matcher(path.getFileName().toString()).matches())
          .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
          .orElse(null);
    }
  }

  private static void copyIfExists(Path source, Path destination, List<String> summary) {
    if (!Files.exists(source)) {
      return;
    }
    try {
      Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      summary.add("copyFailed[" + source + "]=" + e);
    }
  }

  private void assertBuildSuccessful(BuildResult buildResult) {
    assertNotNull(buildResult.getTasks());
    assertFalse(buildResult.getTasks().isEmpty(), "build produced zero tasks");
    for (BuildTask task : buildResult.getTasks()) {
      assertFalse(task.getOutcome() == TaskOutcome.FAILED, "task " + task.getPath() + " failed");
    }
  }
}
