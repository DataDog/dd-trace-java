import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import org.apache.maven.cli.MavenCli
import org.codehaus.plexus.util.FileUtils
import spock.lang.TempDir

import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals

class MavenInstrumentationTest extends CiVisibilityInstrumentationTest {

  private static final int DEPENDENCIES_DOWNLOAD_RETRIES = 3

  @TempDir
  Path projectFolder

  @Override
  def setup() {
    System.setProperty("maven.multiModuleProjectDirectory", projectFolder.toAbsolutePath().toString())
    givenMavenProjectFiles((String) specificationContext.currentIteration.dataVariables.testcaseName)
    givenMavenDependenciesAreLoaded()
    TEST_WRITER.clear() // loading dependencies will generate a test-session span
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_AUTO_CONFIGURATION_ENABLED, "false")
  }

  def "test #testcaseName"() {
    String workingDirectory = projectFolder.toString()

    def exitCode = new MavenCli().doMain(args.toArray(new String[0]), workingDirectory, null, null)

    assertEquals(expectedExitCode, exitCode)
    assertSpansData(testcaseName)

    where:
    testcaseName                                                                      | args                           | expectedExitCode
    "test_maven_build_with_no_tests_generates_spans"                                  | ["-B", "verify"]               | 0
    "test_maven_build_with_incorrect_command_generates_spans"                         | ["-B", "unknownPhase"]         | 1
    "test_maven_build_with_tests_generates_spans"                                     | ["-B", "clean", "test"]        | 0
    "test_maven_build_with_failed_tests_generates_spans"                              | ["-B", "clean", "test"]        | 1
    "test_maven_build_with_tests_in_multiple_modules_generates_spans"                 | ["-B", "clean", "test"]        | 1
    "test_maven_build_with_tests_in_multiple_modules_run_in_parallel_generates_spans" | ["-B", "-T4", "clean", "test"] | 0
    "test_maven_build_with_unit_and_integration_tests_generates_spans"                | ["-B", "verify"]               | 0
    "test_maven_build_with_no_fork_generates_spans"                                   | ["-B", "clean", "test"]        | 0
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
    String[] args = ["org.apache.maven.plugins:maven-dependency-plugin:go-offline"]
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
  String instrumentedLibraryName() {
    return "maven"
  }

  @Override
  String instrumentedLibraryVersion() {
    return MavenCli.getPackage().getImplementationVersion()
  }
}
