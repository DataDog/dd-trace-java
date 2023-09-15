import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import org.apache.maven.cli.MavenCli
import org.apache.maven.lifecycle.LifecycleExecutionException
import org.apache.maven.lifecycle.LifecyclePhaseNotFoundException
import org.codehaus.plexus.util.FileUtils
import spock.lang.TempDir

import java.nio.file.Path
import java.nio.file.Paths

class MavenTest extends CiVisibilityTest {

  private static final int DEPENDENCIES_DOWNLOAD_RETRIES = 3

  @TempDir
  Path projectFolder

  @Override
  void setup() {
    givenMavenProjectFiles(specificationContext.currentIteration.name)
    givenMavenDependenciesAreLoaded()
    TEST_WRITER.clear() // loading dependencies will generate a test-session span
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_AUTO_CONFIGURATION_ENABLED, "false")
  }

  def "test_maven_build_with_no_tests_generates_spans"() {
    given:
    String[] args = ["verify"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 0

    assertTraces(1) {
      trace(1, true) {
        testSessionSpan(it, 0, CIConstants.TEST_SKIP, [:],
        "Maven Integration Tests Project",
        "mvn verify",
        "maven:3.2.5"
        )
      }
    }
  }

  def "test_maven_build_with_incorrect_command_generates_spans"() {
    given:
    String[] args = ["unknownPhase"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 1

    assertTraces(1) {
      trace(1, true) {
        testSessionSpan(it, 0, CIConstants.TEST_FAIL, null,
          "Maven Integration Tests Project",
          "mvn unknownPhase",
          "maven:3.2.5"
          ,
          new LifecyclePhaseNotFoundException(
          "Unknown lifecycle phase \"unknownPhase\". You must specify a valid lifecycle phase or a goal in the format <plugin-prefix>:<goal> or <plugin-group-id>:<plugin-artifact-id>[:<plugin-version>]:<goal>. Available lifecycle phases are: validate, initialize, generate-sources, process-sources, generate-resources, process-resources, compile, process-classes, generate-test-sources, process-test-sources, generate-test-resources, process-test-resources, test-compile, process-test-classes, test, prepare-package, package, pre-integration-test, integration-test, post-integration-test, verify, install, deploy, pre-clean, clean, post-clean, pre-site, site, post-site, site-deploy.",
          "unknownPhase"))
      }
    }
  }

  def "test_maven_build_with_tests_generates_spans"() {
    given:
    String[] args = ["clean", "test"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 0

    assertTraces(1) {
      trace(2, true) {
        def testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS, [:],
        "Maven Integration Tests Project",
        "mvn clean test",
        "maven:3.2.5"
        )
        testModuleSpan(it, 0, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          null, "Maven Integration Tests Project maven-surefire-plugin default-test")
      }
    }
  }

  def "test_maven_build_with_failed_tests_generates_spans"() {
    given:
    String[] args = ["clean", "test"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 1

    assertTraces(1) {
      trace(2, true) {
        def testsFailedException = new LifecycleExecutionException("Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:2.12.4:test (default-test) on project maven-integration-test: There are test failures.\n" +
          "\n" +
          "Please refer to ${workingDirectory}/target/surefire-reports for the individual test results.")

        def testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL, null,
          "Maven Integration Tests Project",
          "mvn clean test",
          "maven:3.2.5"
          ,
          testsFailedException)
        testModuleSpan(it, 0, testSessionId,
          CIConstants.TEST_FAIL,
          [
            (Tags.TEST_COMMAND)  : "mvn clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          testsFailedException,
          "Maven Integration Tests Project maven-surefire-plugin default-test")
      }
    }
  }

  def "test_maven_build_with_tests_in_multiple_modules_generates_spans"() {
    given:
    String[] args = ["clean", "test"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 1

    assertTraces(1) {
      trace(3, true) {
        def testsFailedException = new LifecycleExecutionException("Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:2.12.4:test (default-test) on project maven-integration-test-module-b: There are test failures.\n" +
          "\n" +
          "Please refer to ${workingDirectory}/module-b/target/surefire-reports for the individual test results.")
        def testSessionId = testSessionSpan(it, 2, CIConstants.TEST_FAIL, null,
          "Maven Integration Tests Project",
          "mvn clean test",
          "maven:3.2.5"
          ,
          testsFailedException)
        testModuleSpan(it, 0, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          null, "module-a maven-surefire-plugin default-test")
        testModuleSpan(it, 1, testSessionId,
          CIConstants.TEST_FAIL,
          [
            (Tags.TEST_COMMAND)  : "mvn clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          testsFailedException,
          "module-b maven-surefire-plugin default-test")
      }
    }
  }

  def "test_maven_build_with_tests_in_multiple_modules_run_in_parallel_generates_spans"() {
    given:
    String[] args = ["-T4", "clean", "test"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 0

    assertTraces(1) {
      trace(3, true) {
        def testSessionId = testSessionSpan(it, 2, CIConstants.TEST_PASS, [:],
        "Maven Integration Tests Project",
        "mvn -T4 clean test",
        "maven:3.2.5",
        )
        testModuleSpan(it, 0, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn -T4 clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          null, "module-a maven-surefire-plugin default-test")
        testModuleSpan(it, 1, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn -T4 clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          null, "module-b maven-surefire-plugin default-test")
      }
    }
  }

  def "test_maven_build_with_unit_and_integration_tests_generates_spans"() {
    given:
    String[] args = ["verify"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 0

    assertTraces(1) {
      trace(3, true) {
        def testSessionId = testSessionSpan(it, 2, CIConstants.TEST_PASS, [:],
        "Maven Integration Tests Project",
        "mvn verify",
        "maven:3.2.5"
        )
        testModuleSpan(it, 1, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn verify",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          null, "Maven Integration Tests Project maven-surefire-plugin default-test")
        testModuleSpan(it, 0, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn verify",
            (Tags.TEST_EXECUTION): "maven-failsafe-plugin:integration-test:default",
          ],
          null, "Maven Integration Tests Project maven-failsafe-plugin default")
      }
    }
  }

  def "test_maven_build_with_no_fork_generates_spans"() {
    given:
    String[] args = ["clean", "test"]
    String workingDirectory = projectFolder.toString()

    when:
    def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)

    then:
    exitCode == 0

    // test suite and test case spans are not generated since JUnit instrumentation is not applied
    assertTraces(1) {
      trace(2, true) {
        def testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS, [:],
        "Maven Integration Tests Project",
        "mvn clean test",
        "maven:3.2.5"
        )
        testModuleSpan(it, 0, testSessionId,
          CIConstants.TEST_PASS,
          [
            (Tags.TEST_COMMAND)  : "mvn clean test",
            (Tags.TEST_EXECUTION): "maven-surefire-plugin:test:default-test",
          ],
          null, "Maven Integration Tests Project maven-surefire-plugin default-test")
      }
    }
  }

  private void givenMavenProjectFiles(String projectFilesSources) {
    def projectResourcesUri = this.getClass().getClassLoader().getResource(projectFilesSources).toURI()
    def projectResourcesPath = Paths.get(projectResourcesUri)
    FileUtils.copyDirectoryStructure(projectResourcesPath.toFile(), projectFolder.toFile())
  }

  /**
   * Sometimes Maven has problems downloading project dependencies because of intermittent network issues.
   * Here, in order to reduce flakiness, we ensure that all of the dependencies are loaded (retrying if necessary),
   * before proceeding with running the build
   */
  void givenMavenDependenciesAreLoaded() {
    String[] args = ["dependency:go-offline"]
    String workingDirectory = projectFolder.toString()
    for (int attempt = 0; attempt < DEPENDENCIES_DOWNLOAD_RETRIES; attempt++) {
      def exitCode = new MavenCli().doMain(args, workingDirectory, null, null)
      if (exitCode == 0) {
        return
      }
    }
    throw new AssertionError((Object) "Tried to download dependencies $DEPENDENCIES_DOWNLOAD_RETRIES times and failed")
  }

  @Override
  String expectedOperationPrefix() {
    return "maven"
  }

  @Override
  String expectedTestFramework() {
    // Test framework data is supplied by test framework instrumentations,
    // that are not available in the context of this test
    return null
  }

  @Override
  String expectedTestFrameworkVersion() {
    // Test framework version data is supplied by test framework instrumentations,
    // that are not available in the context of this test
    return null
  }

  @Override
  String component() {
    return "maven"
  }
}
