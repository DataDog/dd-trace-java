package datadog.smoketest


import datadog.trace.api.Config
import datadog.trace.api.Platform
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.util.Strings
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.DistributionLocator
import org.gradle.util.GradleVersion
import org.gradle.wrapper.Download
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
  private static final String TEST_ENVIRONMENT_NAME = "integration-test"

  private static final int GRADLE_DISTRIBUTION_NETWORK_TIMEOUT = 30_000 // Gradle's default timeout is 10s

  private static final String JACOCO_PLUGIN_VERSION = Config.get().ciVisibilityJacocoPluginVersion

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
    Platform.isJ9()
  })
  def "test legacy #projectName, v#gradleVersion"() {
    runGradleTest(gradleVersion, projectName, false, successExpected, false, expectedTraces, expectedCoverages)

    where:
    gradleVersion         | projectName                                        | successExpected | expectedTraces | expectedCoverages
    "3.0"                 | "test-succeed-old-gradle"                          | true            | 5              | 1
    "7.6.4"               | "test-succeed-legacy-instrumentation"              | true            | 5              | 1
    "7.6.4"               | "test-succeed-multi-module-legacy-instrumentation" | true            | 7              | 2
    "7.6.4"               | "test-succeed-multi-forks-legacy-instrumentation"  | true            | 6              | 2
    "7.6.4"               | "test-skip-legacy-instrumentation"                 | true            | 2              | 0
    "7.6.4"               | "test-failed-legacy-instrumentation"               | false           | 4              | 0
    "7.6.4"               | "test-corrupted-config-legacy-instrumentation"     | false           | 1              | 0
  }

  def "test #projectName, v#gradleVersion, configCache: #configurationCache"() {
    runGradleTest(gradleVersion, projectName, configurationCache, successExpected, flakyRetries, expectedTraces, expectedCoverages)

    where:
    gradleVersion         | projectName                                        | configurationCache | successExpected | flakyRetries | expectedTraces | expectedCoverages
    "8.3"                 | "test-succeed-new-instrumentation"                 | false              | true            | false        | 5              | 1
    "8.9"                 | "test-succeed-new-instrumentation"                 | false              | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-new-instrumentation"                 | false              | true            | false        | 5              | 1
    "8.3"                 | "test-succeed-new-instrumentation"                 | true               | true            | false        | 5              | 1
    "8.9"                 | "test-succeed-new-instrumentation"                 | true               | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-new-instrumentation"                 | true               | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-multi-module-new-instrumentation"    | false              | true            | false        | 7              | 2
    LATEST_GRADLE_VERSION | "test-succeed-multi-forks-new-instrumentation"     | false              | true            | false        | 6              | 2
    LATEST_GRADLE_VERSION | "test-skip-new-instrumentation"                    | false              | true            | false        | 2              | 0
    LATEST_GRADLE_VERSION | "test-failed-new-instrumentation"                  | false              | false           | false        | 4              | 0
    LATEST_GRADLE_VERSION | "test-corrupted-config-new-instrumentation"        | false              | false           | false        | 1              | 0
    LATEST_GRADLE_VERSION | "test-succeed-junit-5"                             | false              | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-failed-flaky-retries"                        | false              | false           | true         | 8              | 0
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
    String agentShadowJar = System.getProperty("datadog.smoketest.agent.shadowJar.path")
    assert new File(agentShadowJar).isFile()

    def ddApiKeyPath = testKitFolder.resolve(".dd.api.key")
    Files.write(ddApiKeyPath, "dummy".getBytes())

    def gradleProperties =
      "org.gradle.jvmargs=" +
      // for convenience when debugging locally
      (System.getenv("DD_CIVISIBILITY_SMOKETEST_DEBUG_PARENT") != null ? "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 " : "") +
      "-javaagent:${agentShadowJar}=" +
      // for convenience when debugging locally
      (System.getenv("DD_CIVISIBILITY_SMOKETEST_DEBUG_CHILD") != null ? "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT)}=5055," : "") +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.TRACE_DEBUG)}=true," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.ENV)}=${TEST_ENVIRONMENT_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.API_KEY_FILE)}=${ddApiKeyPath.toAbsolutePath().toString()}," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED)}=false," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED)}=false," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION)}=$JACOCO_PLUGIN_VERSION," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL)}=${mockBackend.intakeUrl}"

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
    GradleRunner gradleRunner = GradleRunner.create()
      .withTestKitDir(testKitFolder.toFile())
      .withProjectDir(projectFolder.toFile())
      .withGradleVersion(gradleVersion)
      .withArguments(arguments)
      .forwardOutput()

    println "${new Date()}: $specificationContext.currentIteration.displayName - Starting Gradle run"
    def buildResult = successExpected ? gradleRunner.build() : gradleRunner.buildAndFail()
    println "${new Date()}: $specificationContext.currentIteration.displayName - Finished Gradle run"
    buildResult
  }

  private void assertBuildSuccessful(buildResult) {
    assert buildResult.tasks != null
    assert buildResult.tasks.size() > 0
    for (def task : buildResult.tasks) {
      assert task.outcome != TaskOutcome.FAILED
    }
  }
}
