package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.DistributionLocator
import org.gradle.util.GradleVersion
import org.gradle.wrapper.Download
import org.gradle.wrapper.GradleUserHomeLookup
import org.gradle.wrapper.Install
import org.gradle.wrapper.PathAssembler
import org.gradle.wrapper.WrapperConfiguration
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GradleDaemonSmokeTest extends AbstractGradleTest {

  private static final String TEST_SERVICE_NAME = "test-gradle-service"

  private static final int GRADLE_DISTRIBUTION_NETWORK_TIMEOUT = 30_000 // Gradle's default timeout is 10s

  // TODO: Gradle daemons started by the TestKit have an idle period of 3 minutes
  //  so by the time tests finish, at least some of the daemons are still alive.
  //  Because of that the temporary TestKit folder cannot be fully deleted
  @Shared
  @TempDir
  Path testKitFolder

  def setupSpec() {
    givenGradleProperties()
  }

  @IgnoreIf(reason = "Jacoco plugin does not work with OpenJ9 in older Gradle versions", value = {
    JavaVirtualMachine.isJ9()
  })
  def "test legacy #projectName, v#gradleVersion"() {
    runGradleTest(gradleVersion, projectName, false, successExpected, false, expectedTraces, expectedCoverages)

    where:
    gradleVersion | projectName                                        | successExpected | expectedTraces | expectedCoverages
    "3.5"         | "test-succeed-old-gradle"                          | true            | 5              | 1
    "7.6.4"       | "test-succeed-legacy-instrumentation"              | true            | 5              | 1
    "7.6.4"       | "test-succeed-multi-module-legacy-instrumentation" | true            | 7              | 2
    "7.6.4"       | "test-succeed-multi-forks-legacy-instrumentation"  | true            | 6              | 2
    "7.6.4"       | "test-skip-legacy-instrumentation"                 | true            | 2              | 0
    "7.6.4"       | "test-failed-legacy-instrumentation"               | false           | 4              | 0
    "7.6.4"       | "test-corrupted-config-legacy-instrumentation"     | false           | 1              | 0
  }

  def "test #projectName, v#gradleVersion, configCache: #configurationCache"() {
    runGradleTest(gradleVersion, projectName, configurationCache, successExpected, flakyRetries, expectedTraces, expectedCoverages)

    where:
    gradleVersion         | projectName                                     | configurationCache | successExpected | flakyRetries | expectedTraces | expectedCoverages
    "8.3"                 | "test-succeed-new-instrumentation"              | false              | true            | false        | 5              | 1
    "8.9"                 | "test-succeed-new-instrumentation"              | false              | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-new-instrumentation"              | false              | true            | false        | 5              | 1
    "8.3"                 | "test-succeed-new-instrumentation"              | true               | true            | false        | 5              | 1
    "8.9"                 | "test-succeed-new-instrumentation"              | true               | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-new-instrumentation"              | true               | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-multi-module-new-instrumentation" | false              | true            | false        | 7              | 2
    LATEST_GRADLE_VERSION | "test-succeed-multi-forks-new-instrumentation"  | false              | true            | false        | 6              | 2
    LATEST_GRADLE_VERSION | "test-skip-new-instrumentation"                 | false              | true            | false        | 2              | 0
    LATEST_GRADLE_VERSION | "test-failed-new-instrumentation"               | false              | false           | false        | 4              | 0
    LATEST_GRADLE_VERSION | "test-corrupted-config-new-instrumentation"     | false              | false           | false        | 1              | 0
    LATEST_GRADLE_VERSION | "test-succeed-junit-5"                          | false              | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-failed-flaky-retries"                     | false              | false           | true         | 8              | 0
    LATEST_GRADLE_VERSION | "test-succeed-gradle-plugin-test"               | false              | true            | false        | 5              | 0
  }

  def "test junit4 class ordering v#gradleVersion"() {
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles(projectName)
    ensureDependenciesDownloaded(gradleVersion)

    mockBackend.givenKnownTests(true)
    for (flakyTest in flakyTests) {
      mockBackend.givenFlakyTest(":test", flakyTest.getSuite(), flakyTest.getName())
      mockBackend.givenKnownTest(":test", flakyTest.getSuite(), flakyTest.getName())
    }

    BuildResult buildResult = runGradleTests(gradleVersion, true, false)
    assertBuildSuccessful(buildResult)

    verifyTestOrder(mockBackend.waitForEvents(eventsNumber), expectedOrder)

    where:
    gradleVersion         | projectName                           | flakyTests | expectedOrder | eventsNumber
    "7.6.4"               | "test-succeed-junit-4-class-ordering" | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ]                                                                          | [
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedC", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another")
    ]                                                                                          | 15
    LATEST_GRADLE_VERSION | "test-succeed-junit-4-class-ordering" | [
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed")
    ]                                                                          | [
      test("datadog.smoke.TestSucceedC", "test_succeed"),
      test("datadog.smoke.TestSucceedC", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed_another"),
      test("datadog.smoke.TestSucceedA", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed"),
      test("datadog.smoke.TestSucceedB", "test_succeed_another")
    ]                                                                                          | 15
  }

  private runGradleTest(String gradleVersion, String projectName, boolean configurationCache, boolean successExpected, boolean flakyRetries, int expectedTraces, int expectedCoverages) {
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenConfigurationCacheIsCompatibleWithCurrentPlatform(configurationCache)
    givenGradleProjectFiles(projectName)
    ensureDependenciesDownloaded(gradleVersion)

    mockBackend.givenFlakyRetries(flakyRetries)
    mockBackend.givenFlakyTest(":test", "datadog.smoke.TestFailed", "test_failed")

    mockBackend.givenTestsSkipping(true)
    mockBackend.givenSkippableTest(":test", "datadog.smoke.TestSucceed", "test_to_skip_with_itr", [:])

    BuildResult buildResult = runGradleTests(gradleVersion, successExpected, configurationCache)

    if (successExpected) {
      assertBuildSuccessful(buildResult)
    }

    verifyEventsAndCoverages(projectName, "gradle", gradleVersion, mockBackend.waitForEvents(expectedTraces), mockBackend.waitForCoverages(expectedCoverages))

    if (configurationCache) {
      // if configuration cache is enabled, run the build one more time
      // to verify that building with existing configuration cache entry works
      BuildResult buildResultWithConfigCacheEntry = runGradleTests(gradleVersion, successExpected, configurationCache)

      assertBuildSuccessful(buildResultWithConfigCacheEntry)
      verifyEventsAndCoverages(projectName, "gradle", gradleVersion, mockBackend.waitForEvents(expectedTraces), mockBackend.waitForCoverages(expectedCoverages))
    }
  }

  private void givenGradleProperties() {
    assert new File(AGENT_JAR).isFile()

    def ddApiKeyPath = testKitFolder.resolve(".dd.api.key")
    Files.write(ddApiKeyPath, "dummy".getBytes())

    def additionalArgs = [
      (GeneralConfig.API_KEY_FILE): ddApiKeyPath.toAbsolutePath().toString(),
      (CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION): JACOCO_PLUGIN_VERSION,
      /*
       * Some of the smoke tests (in particular the one with the Gradle plugin), are using Gradle Test Kit for their tests.
       * Gradle Test Kit needs to do a "chmod" when starting a Gradle Daemon.
       * This "chmod" operation is traced by datadog.trace.instrumentation.java.lang.ProcessImplInstrumentation and is reported as a span.
       * The problem is that the "chmod" only happens when running in CI (could be due to differences in OS or FS permissions),
       * so when running the tests locally, the "chmod" span is not there.
       * This causes the tests to fail because the number of reported traces is different.
       * To avoid this discrepancy between local and CI runs, we disable tracing instrumentations.
       */
      (TraceInstrumentationConfig.TRACE_ENABLED): "false"
    ]
    def arguments = buildJvmArguments(mockBackend.intakeUrl, TEST_SERVICE_NAME, additionalArgs)

    def gradleProperties = "org.gradle.jvmargs=${arguments.join(" ")}".toString()
    Files.write(testKitFolder.resolve("gradle.properties"), gradleProperties.getBytes())
  }

  private BuildResult runGradleTests(String gradleVersion, boolean successExpected = true, boolean configurationCache = false) {
    def arguments = ["test", "--stacktrace"]
    if (gradleVersion > "4.5") {
      // warning mode available starting from Gradle 4.5
      arguments += ["--warning-mode", "all"]
    }
    if (configurationCache) {
      arguments += ["--configuration-cache", "--rerun-tasks"]
    }
    BuildResult buildResult = runGradle(gradleVersion, arguments, successExpected)
    buildResult
  }

  /**
   * Sometimes Gradle Test Kit fails because it cannot download the required Gradle distribution
   * due to intermittent network issues.
   * This method performs the download manually (if needed) with increased timeout (30s vs default 10s).
   * Retry logic (3 retries) is already present in org.gradle.wrapper.Install
   */
  private ensureDependenciesDownloaded(String gradleVersion) {
    try {
      println "${new Date()}: $specificationContext.currentIteration.displayName - Starting dependencies download"

      def logger = new org.gradle.wrapper.Logger(false)
      def download = new Download(logger, "Gradle Tooling API", GradleVersion.current().getVersion(), GRADLE_DISTRIBUTION_NETWORK_TIMEOUT)

      def userHomeDir = GradleUserHomeLookup.gradleUserHome()
      def projectDir = projectFolder.toFile()
      def install = new Install(logger, download, new PathAssembler(userHomeDir, projectDir))

      def configuration = new WrapperConfiguration()
      def distribution = new DistributionLocator().getDistributionFor(GradleVersion.version(gradleVersion))
      configuration.setDistribution(distribution)
      configuration.setNetworkTimeout(GRADLE_DISTRIBUTION_NETWORK_TIMEOUT)

      // this will download distribution (if not downloaded yet to userHomeDir) and verify its SHA
      install.createDist(configuration)

      println "${new Date()}: $specificationContext.currentIteration.displayName - Finished dependencies download"
    } catch (Exception e) {
      println "${new Date()}: $specificationContext.currentIteration.displayName " +
        "- Failed to install Gradle distribution, will proceed to run test kit hoping for the best: $e"
    }
  }

  private runGradle(String gradleVersion, List<String> arguments, boolean successExpected) {
    def buildEnv = ["GRADLE_VERSION": gradleVersion]

    def mavenRepositoryProxy = System.getenv("MAVEN_REPOSITORY_PROXY")
    if (mavenRepositoryProxy != null) {
      buildEnv += ["MAVEN_REPOSITORY_PROXY": System.getenv("MAVEN_REPOSITORY_PROXY")]
    }

    GradleRunner gradleRunner = GradleRunner.create()
      .withTestKitDir(testKitFolder.toFile())
      .withProjectDir(projectFolder.toFile())
      .withGradleVersion(gradleVersion)
      .withArguments(arguments)
      .withEnvironment(buildEnv)
      .forwardOutput()

    println "${new Date()}: $specificationContext.currentIteration.displayName - Starting Gradle run"
    try {
      def buildResult = successExpected ? gradleRunner.build() : gradleRunner.buildAndFail()
      println "${new Date()}: $specificationContext.currentIteration.displayName - Finished Gradle run"
      return buildResult
    } catch (Exception e) {
      def daemonLog = Files.list(testKitFolder.resolve("test-kit-daemon/" + gradleVersion)).filter(p -> p.toString().endsWith("log")).findAny().orElse(null)
      if (daemonLog != null) {
        println "=============================================================="
        println "${new Date()}: $specificationContext.currentIteration.displayName - Gradle Daemon log:\n${new String(Files.readAllBytes(daemonLog))}"
        println "=============================================================="
      }
      throw e
    }
  }

  private void assertBuildSuccessful(buildResult) {
    assert buildResult.tasks != null
    assert buildResult.tasks.size() > 0
    for (def task : buildResult.tasks) {
      assert task.outcome != TaskOutcome.FAILED
    }
  }
}
