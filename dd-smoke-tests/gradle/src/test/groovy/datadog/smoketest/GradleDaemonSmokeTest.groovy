package datadog.smoketest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.api.Config
import datadog.trace.api.Platform
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.civisibility.CiVisibilitySmokeTest
import datadog.trace.util.Strings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils
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
import org.junit.jupiter.api.Assumptions
import spock.lang.Shared
import spock.lang.TempDir
import spock.lang.Unroll
import spock.util.environment.Jvm

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

@Unroll
class GradleDaemonSmokeTest extends CiVisibilitySmokeTest {

  private static final String LATEST_GRADLE_VERSION = getLatestGradleVersion()

  private static final String TEST_SERVICE_NAME = "test-gradle-service"
  private static final String TEST_ENVIRONMENT_NAME = "integration-test"

  // test resources use this instead of ".gradle" to avoid unwanted evaluation
  private static final String GRADLE_TEST_RESOURCE_EXTENSION = ".gradleTest"
  private static final String GRADLE_REGULAR_EXTENSION = ".gradle"

  private static final int GRADLE_DISTRIBUTION_NETWORK_TIMEOUT = 30_000 // Gradle's default timeout is 10s

  private static final String JACOCO_PLUGIN_VERSION = Config.get().ciVisibilityJacocoPluginVersion

  // TODO: Gradle daemons started by the TestKit have an idle period of 3 minutes
  //  so by the time tests finish, at least some of the daemons are still alive.
  //  Because of that the temporary TestKit folder cannot be fully deleted
  @Shared
  @TempDir
  Path testKitFolder

  @TempDir
  Path projectFolder

  def setupSpec() {
    givenGradleProperties()
  }

  def "test #projectName, v#gradleVersion, configCache: #configurationCache"() {
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenConfigurationCacheIsCompatibleWithCurrentPlatform(configurationCache)
    givenGradleProjectFiles(projectName)
    givenFlakyRetriesEnabled(flakyRetries)
    ensureDependenciesDownloaded(gradleVersion)

    BuildResult buildResult = runGradleTests(gradleVersion, successExpected, configurationCache)

    if (successExpected) {
      assertBuildSuccessful(buildResult)
    }
    verifyEventsAndCoverages(projectName, "gradle", gradleVersion, expectedTraces, expectedCoverages)

    if (configurationCache) {
      // if configuration cache is enabled, run the build one more time
      // to verify that building with existing configuration cache entry works
      BuildResult buildResultWithConfigCacheEntry = runGradleTests(gradleVersion, successExpected, configurationCache)

      assertBuildSuccessful(buildResultWithConfigCacheEntry)
      verifyEventsAndCoverages(projectName, "gradle", gradleVersion, expectedTraces, expectedCoverages)
    }

    where:
    gradleVersion         | projectName                                        | configurationCache | successExpected | flakyRetries | expectedTraces | expectedCoverages
    "3.0"                 | "test-succeed-old-gradle"                          | false              | true            | false        | 5              | 1
    "4.0"                 | "test-succeed-legacy-instrumentation"              | false              | true            | false        | 5              | 1
    "5.0"                 | "test-succeed-legacy-instrumentation"              | false              | true            | false        | 5              | 1
    "6.0"                 | "test-succeed-legacy-instrumentation"              | false              | true            | false        | 5              | 1
    "7.6.3"               | "test-succeed-legacy-instrumentation"              | false              | true            | false        | 5              | 1
    "8.3"                 | "test-succeed-new-instrumentation"                 | false              | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-new-instrumentation"                 | false              | true            | false        | 5              | 1
    "8.3"                 | "test-succeed-new-instrumentation"                 | true               | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-succeed-new-instrumentation"                 | true               | true            | false        | 5              | 1
    "7.6.3"               | "test-succeed-multi-module-legacy-instrumentation" | false              | true            | false        | 7              | 2
    LATEST_GRADLE_VERSION | "test-succeed-multi-module-new-instrumentation"    | false              | true            | false        | 7              | 2
    "7.6.3"               | "test-succeed-multi-forks-legacy-instrumentation"  | false              | true            | false        | 6              | 2
    LATEST_GRADLE_VERSION | "test-succeed-multi-forks-new-instrumentation"     | false              | true            | false        | 6              | 2
    "7.6.3"               | "test-skip-legacy-instrumentation"                 | false              | true            | false        | 2              | 0
    LATEST_GRADLE_VERSION | "test-skip-new-instrumentation"                    | false              | true            | false        | 2              | 0
    "7.6.3"               | "test-failed-legacy-instrumentation"               | false              | false           | false        | 4              | 0
    LATEST_GRADLE_VERSION | "test-failed-new-instrumentation"                  | false              | false           | false        | 4              | 0
    "7.6.3"               | "test-corrupted-config-legacy-instrumentation"     | false              | false           | false        | 1              | 0
    LATEST_GRADLE_VERSION | "test-corrupted-config-new-instrumentation"        | false              | false           | false        | 1              | 0
    LATEST_GRADLE_VERSION | "test-succeed-junit-5"                             | false              | true            | false        | 5              | 1
    LATEST_GRADLE_VERSION | "test-failed-flaky-retries"                        | false              | false           | true         | 8              | 0
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
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.ENV)}=${TEST_ENVIRONMENT_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.API_KEY_FILE)}=${ddApiKeyPath.toAbsolutePath().toString()}," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED)}=false," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED)}=false," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION)}=$JACOCO_PLUGIN_VERSION," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_SEGMENTS_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL)}=${intakeServer.address.toString()}"

    Files.write(testKitFolder.resolve("gradle.properties"), gradleProperties.getBytes())
  }

  private void givenGradleProjectFiles(String projectFilesSources) {
    def projectResourcesUri = this.getClass().getClassLoader().getResource(projectFilesSources).toURI()
    def projectResourcesPath = Paths.get(projectResourcesUri)
    FileUtils.copyDirectory(projectResourcesPath.toFile(), projectFolder.toFile())

    Files.walkFileTree(projectFolder, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(GRADLE_TEST_RESOURCE_EXTENSION)) {
            def fileWithFixedExtension = Paths.get(file.toString().replace(GRADLE_TEST_RESOURCE_EXTENSION, GRADLE_REGULAR_EXTENSION))
            Files.move(file, fileWithFixedExtension)
          }
          return FileVisitResult.CONTINUE
        }
      })

    // creating empty .git directory so that the tracer could detect projectFolder as repo root
    Files.createDirectory(projectFolder.resolve(".git"))
  }

  private BuildResult runGradleTests(String gradleVersion, boolean successExpected = true, boolean configurationCache = false) {
    def arguments = ["test", "--stacktrace"]
    if (gradleVersion > "5.6") {
      // fail on warnings is available starting from Gradle 5.6
      arguments += ["--warning-mode", "fail"]
    } else if (gradleVersion > "4.5") {
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

  void givenGradleVersionIsCompatibleWithCurrentJvm(String gradleVersion) {
    Assumptions.assumeTrue(isSupported(gradleVersion),
      "Current JVM " + Jvm.current.javaVersion + " does not support Gradle version " + gradleVersion)
  }

  private static boolean isSupported(String gradleVersion) {
    // https://docs.gradle.org/current/userguide/compatibility.html
    if (Jvm.current.java21Compatible) {
      return gradleVersion >= "8.4"
    } else if (Jvm.current.java20) {
      return gradleVersion >= "8.1"
    } else if (Jvm.current.java19) {
      return gradleVersion >= "7.6"
    } else if (Jvm.current.java18) {
      return gradleVersion >= "7.5"
    } else if (Jvm.current.java17) {
      return gradleVersion >= "7.3"
    } else if (Jvm.current.java16) {
      return gradleVersion >= "7.0"
    } else if (Jvm.current.java15) {
      return gradleVersion >= "6.7"
    } else if (Jvm.current.java14) {
      return gradleVersion >= "6.3"
    } else if (Jvm.current.java13) {
      return gradleVersion >= "6.0"
    } else if (Jvm.current.java12) {
      return gradleVersion >= "5.4"
    } else if (Jvm.current.java11) {
      return gradleVersion >= "5.0"
    } else if (Jvm.current.java10) {
      return gradleVersion >= "4.7"
    } else if (Jvm.current.java9) {
      return gradleVersion >= "4.3"
    } else if (Jvm.current.java8) {
      return gradleVersion >= "2.0"
    }
    return false
  }

  void givenConfigurationCacheIsCompatibleWithCurrentPlatform(boolean configurationCacheEnabled) {
    if (configurationCacheEnabled) {
      Assumptions.assumeFalse(Platform.isIbm8(), "Configuration cache is not compatible with IBM 8")
    }
  }

  private static String getLatestGradleVersion() {
    OkHttpClient client = new OkHttpClient()
    Request request = new Request.Builder().url("https://services.gradle.org/versions/current").build()
    try (Response response = client.newCall(request).execute()) {
      if (!response.successful) {
        return GradleVersion.current().version
      }
      def responseBody = response.body().string()
      ObjectMapper mapper = new ObjectMapper()
      JsonNode root = mapper.readTree(responseBody)
      return root.get("version").asText()
    }
  }

  private void givenFlakyRetriesEnabled(boolean flakyRetries) {
    this.flakyRetriesEnabled = flakyRetries
  }
}
