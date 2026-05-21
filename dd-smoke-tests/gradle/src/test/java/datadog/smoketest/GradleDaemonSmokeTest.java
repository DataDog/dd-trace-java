package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.util.GradleVersion;
import org.gradle.util.internal.DistributionLocator;
import org.gradle.wrapper.Download;
import org.gradle.wrapper.GradleUserHomeLookup;
import org.gradle.wrapper.Install;
import org.gradle.wrapper.PathAssembler;
import org.gradle.wrapper.WrapperConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

class GradleDaemonSmokeTest extends AbstractGradleTest {

  private static final String TEST_SERVICE_NAME = "test-gradle-service";

  // Gradle's default timeout is 10s
  private static final int GRADLE_DISTRIBUTION_NETWORK_TIMEOUT = 30_000;

  @TempDir static Path testKitFolder;

  @TableTest({
    "gradleVersion | projectName                                      | successExpected | expectedTraces | expectedCoverages",
    "3.5           | test-succeed-old-gradle                          | true            | 5              | 1                ",
    "7.6.4         | test-succeed-legacy-instrumentation              | true            | 5              | 1                ",
    "7.6.4         | test-succeed-multi-module-legacy-instrumentation | true            | 7              | 2                ",
    "7.6.4         | test-succeed-multi-forks-legacy-instrumentation  | true            | 6              | 2                ",
    "7.6.4         | test-skip-legacy-instrumentation                 | true            | 2              | 0                ",
    "7.6.4         | test-failed-legacy-instrumentation               | false           | 4              | 0                ",
    "7.6.4         | test-corrupted-config-legacy-instrumentation     | false           | 1              | 0                "
  })
  @ParameterizedTest(name = "test legacy {1}, v{0}")
  void testLegacy(
      String gradleVersion,
      String projectName,
      boolean successExpected,
      int expectedTraces,
      int expectedCoverages,
      TestInfo testInfo)
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
        expectedCoverages,
        testInfo);
  }

  @TableTest({
    "gradleVersion | projectName                                   | configurationCache | successExpected | flakyRetries | expectedTraces | expectedCoverages",
    "8.3           | test-succeed-new-instrumentation              | false              | true            | false        | 5              | 1                ",
    "8.9           | test-succeed-new-instrumentation              | false              | true            | false        | 5              | 1                ",
    "latest        | test-succeed-new-instrumentation              | false              | true            | false        | 5              | 1                ",
    "8.3           | test-succeed-new-instrumentation              | true               | true            | false        | 5              | 1                ",
    "8.9           | test-succeed-new-instrumentation              | true               | true            | false        | 5              | 1                ",
    "latest        | test-succeed-new-instrumentation              | true               | true            | false        | 5              | 1                ",
    "latest        | test-succeed-multi-module-new-instrumentation | false              | true            | false        | 7              | 2                ",
    "latest        | test-succeed-multi-forks-new-instrumentation  | false              | true            | false        | 6              | 2                ",
    "latest        | test-skip-new-instrumentation                 | false              | true            | false        | 2              | 0                ",
    "latest        | test-failed-new-instrumentation               | false              | false           | false        | 4              | 0                ",
    "latest        | test-corrupted-config-new-instrumentation     | false              | false           | false        | 1              | 0                ",
    "latest        | test-succeed-junit-5                          | false              | true            | false        | 5              | 1                ",
    "latest        | test-failed-flaky-retries                     | false              | false           | true         | 8              | 0                ",
    "9.3.1         | test-succeed-gradle-plugin-test               | false              | true            | false        | 5              | 0                "
  })
  @ParameterizedTest(name = "test {1}, v{0}, configCache: {2}")
  void testNew(
      String gradleVersion,
      String projectName,
      boolean configurationCache,
      boolean successExpected,
      boolean flakyRetries,
      int expectedTraces,
      int expectedCoverages,
      TestInfo testInfo)
      throws IOException {
    String resolvedGradleVersion = resolveLatest(gradleVersion);
    runGradleTest(
        resolvedGradleVersion,
        projectName,
        configurationCache,
        successExpected,
        flakyRetries,
        expectedTraces,
        expectedCoverages,
        testInfo);
  }

  // TODO: add back LATEST_GRADLE_VERSION after fixing ordering on Gradle 9.3.0
  @TableTest({
    "gradleVersion | projectName                         | flakyTests                                                                                                                                | expectedOrder                                                                                                                                                                                                                                                                              | eventsNumber",
    "7.6.4         | test-succeed-junit-4-class-ordering | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          ",
    "9.2.1         | test-succeed-junit-4-class-ordering | ['datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed'] | ['datadog.smoke.TestSucceedC:test_succeed', 'datadog.smoke.TestSucceedC:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed_another', 'datadog.smoke.TestSucceedA:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed', 'datadog.smoke.TestSucceedB:test_succeed_another'] | 15          "
  })
  @ParameterizedTest(name = "test junit4 class ordering v{0}")
  void testJunit4ClassOrdering(
      String gradleVersion,
      String projectName,
      List<TestFQN> flakyTests,
      List<TestFQN> expectedOrder,
      int eventsNumber,
      TestInfo testInfo)
      throws IOException {
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion);
    givenGradleProjectFiles(projectName);
    givenGradleProjectProperties();
    ensureDependenciesDownloaded(gradleVersion, testInfo);

    mockBackend.givenKnownTests(true);
    for (TestFQN flakyTest : flakyTests) {
      mockBackend.givenFlakyTest(":test", flakyTest.getSuite(), flakyTest.getName());
      mockBackend.givenKnownTest(":test", flakyTest.getSuite(), flakyTest.getName());
    }

    BuildResult buildResult = runGradleTests(gradleVersion, true, false, testInfo);
    assertBuildSuccessful(buildResult);

    verifyTestOrder(mockBackend.waitForEvents(eventsNumber), expectedOrder);
  }

  private static String resolveLatest(String gradleVersion) {
    return "latest".equals(gradleVersion) ? LATEST_GRADLE_VERSION : gradleVersion;
  }

  @TypeConverter
  public static TestFQN toTestFQN(String value) {
    int colon = value.indexOf(':');
    return new TestFQN(value.substring(0, colon), value.substring(colon + 1));
  }

  private void runGradleTest(
      String gradleVersion,
      String projectName,
      boolean configurationCache,
      boolean successExpected,
      boolean flakyRetries,
      int expectedTraces,
      int expectedCoverages,
      TestInfo testInfo)
      throws IOException {
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion);
    givenConfigurationCacheIsCompatibleWithCurrentPlatform(configurationCache);
    givenGradleProjectFiles(projectName);
    givenGradleProjectProperties();
    ensureDependenciesDownloaded(gradleVersion, testInfo);

    mockBackend.givenFlakyRetries(flakyRetries);
    mockBackend.givenFlakyTest(":test", "datadog.smoke.TestFailed", "test_failed");

    mockBackend.givenTestsSkipping(true);
    mockBackend.givenSkippableTest(
        ":test", "datadog.smoke.TestSucceed", "test_to_skip_with_itr", Collections.emptyMap());

    BuildResult buildResult =
        runGradleTests(gradleVersion, successExpected, configurationCache, testInfo);

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
          runGradleTests(gradleVersion, successExpected, configurationCache, testInfo);

      assertBuildSuccessful(buildResultWithConfigCacheEntry);
      verifyEventsAndCoverages(
          projectName,
          "gradle",
          gradleVersion,
          mockBackend.waitForEvents(expectedTraces),
          mockBackend.waitForCoverages(expectedCoverages));
    }
  }

  private void givenGradleProjectProperties() throws IOException {
    assertTrue(new java.io.File(AGENT_JAR).isFile());

    Path ddApiKeyPath = testKitFolder.resolve(".dd.api.key");
    Files.write(ddApiKeyPath, "dummy".getBytes());

    Map<String, String> additionalArgs = new HashMap<>();
    additionalArgs.put(GeneralConfig.API_KEY_FILE, ddApiKeyPath.toAbsolutePath().toString());
    additionalArgs.put(
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
    additionalArgs.put(TraceInstrumentationConfig.TRACE_ENABLED, "false");
    List<String> arguments =
        buildJvmArguments(mockBackend.getIntakeUrl(), TEST_SERVICE_NAME, additionalArgs);

    String gradleProperties = "org.gradle.jvmargs=" + String.join(" ", arguments);
    // Write to projectFolder (per-test) instead of testKitFolder (shared), so each
    // Gradle daemon gets its own unique preference directory.
    Files.write(projectFolder.resolve("gradle.properties"), gradleProperties.getBytes());
  }

  private BuildResult runGradleTests(
      String gradleVersion, boolean successExpected, boolean configurationCache, TestInfo testInfo)
      throws IOException {
    List<String> arguments = new java.util.ArrayList<>(Arrays.asList("test", "--stacktrace"));
    if (gradleVersion.compareTo("4.5") > 0) {
      // warning mode available starting from Gradle 4.5
      arguments.addAll(Arrays.asList("--warning-mode", "all"));
    }
    if (configurationCache) {
      arguments.addAll(Arrays.asList("--configuration-cache", "--rerun-tasks"));
    }
    return runGradle(gradleVersion, arguments, successExpected, testInfo);
  }

  /**
   * Sometimes Gradle Test Kit fails because it cannot download the required Gradle distribution due
   * to intermittent network issues. This method performs the download manually (if needed) with
   * increased timeout (30s vs default 10s). Retry logic (3 retries) is already present in {@code
   * org.gradle.wrapper.Install}.
   */
  private void ensureDependenciesDownloaded(String gradleVersion, TestInfo testInfo) {
    try {
      System.out.println(
          new Date() + ": " + testInfo.getDisplayName() + " - Starting dependencies download");

      org.gradle.wrapper.Logger logger = new org.gradle.wrapper.Logger(false);
      Download download =
          new Download(
              logger,
              "Gradle Tooling API",
              GradleVersion.current().getVersion(),
              GRADLE_DISTRIBUTION_NETWORK_TIMEOUT);

      java.io.File userHomeDir = GradleUserHomeLookup.gradleUserHome();
      java.io.File projectDir = projectFolder.toFile();
      Install install = new Install(logger, download, new PathAssembler(userHomeDir, projectDir));

      WrapperConfiguration configuration = new WrapperConfiguration();
      configuration.setDistribution(
          new DistributionLocator().getDistributionFor(GradleVersion.version(gradleVersion)));
      configuration.setNetworkTimeout(GRADLE_DISTRIBUTION_NETWORK_TIMEOUT);

      // This will download distribution (if not downloaded yet to userHomeDir) and verify its SHA.
      install.createDist(configuration);

      System.out.println(
          new Date() + ": " + testInfo.getDisplayName() + " - Finished dependencies download");
    } catch (Exception e) {
      System.out.println(
          new Date()
              + ": "
              + testInfo.getDisplayName()
              + " - Failed to install Gradle distribution, will proceed to run test kit hoping for the best: "
              + e);
    }
  }

  private BuildResult runGradle(
      String gradleVersion, List<String> arguments, boolean successExpected, TestInfo testInfo)
      throws IOException {
    Map<String, String> buildEnv = new HashMap<>();
    buildEnv.put("GRADLE_VERSION", gradleVersion);

    String mavenRepositoryProxy = System.getenv("MAVEN_REPOSITORY_PROXY");
    if (mavenRepositoryProxy != null) {
      buildEnv.put("MAVEN_REPOSITORY_PROXY", mavenRepositoryProxy);
    }

    GradleRunner gradleRunner =
        GradleRunner.create()
            .withTestKitDir(testKitFolder.toFile())
            .withProjectDir(projectFolder.toFile())
            .withGradleVersion(gradleVersion)
            .withArguments(arguments)
            .withEnvironment(buildEnv)
            .forwardOutput();

    System.out.println(new Date() + ": " + testInfo.getDisplayName() + " - Starting Gradle run");
    try {
      BuildResult buildResult =
          successExpected ? gradleRunner.build() : gradleRunner.buildAndFail();
      System.out.println(new Date() + ": " + testInfo.getDisplayName() + " - Finished Gradle run");
      return buildResult;
    } catch (Exception e) {
      Path daemonLog =
          Files.list(testKitFolder.resolve("test-kit-daemon/" + gradleVersion))
              .filter(p -> p.toString().endsWith("log"))
              .findAny()
              .orElse(null);
      if (daemonLog != null) {
        System.out.println("==============================================================");
        System.out.println(
            new Date()
                + ": "
                + testInfo.getDisplayName()
                + " - Gradle Daemon log:\n"
                + new String(Files.readAllBytes(daemonLog)));
        System.out.println("==============================================================");
      }
      throw new RuntimeException(e);
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
