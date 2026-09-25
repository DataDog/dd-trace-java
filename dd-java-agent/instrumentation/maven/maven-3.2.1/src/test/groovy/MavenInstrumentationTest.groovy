import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import org.apache.maven.cli.MavenCli
import org.codehaus.plexus.util.FileUtils
import org.slf4j.MDC
import spock.lang.TempDir

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.assertEquals

class MavenInstrumentationTest extends CiVisibilityInstrumentationTest {

  private static final int DEPENDENCIES_DOWNLOAD_RETRIES = 3

  @TempDir
  Path projectFolder

  @Override
  def setup() {
    // Workaround for maven-surefire 3.5.5 bug (https://github.com/apache/maven-surefire/pull/3241):
    // ThreadedStreamConsumer$Pumper.run() calls MDC.setContextMap(MDC.getCopyOfContextMap()),
    // but LogbackMDCAdapter.getCopyOfContextMap() returns null when MDC is uninitialized,
    // and LogbackMDCAdapter.setContextMap(null) throws NPE via HashMap.putAll(null).
    // Pre-initializing MDC ensures getCopyOfContextMap() returns an empty map instead of null.
    MDC.put("_init", "true")
    MDC.remove("_init")

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
    def exitCode = executeMaven(args)

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

  def "Maven test status follows resolved skip configuration: #skipArgument, POM skipTests=#pomSkipTests"() {
    given:
    if (pomSkipTests != null) {
      def pom = projectFolder.resolve("pom.xml").toFile()
      pom.text = pom.text.replace("<artifactId>maven-surefire-plugin</artifactId>",
        "<artifactId>maven-surefire-plugin</artifactId><configuration><skipTests>${pomSkipTests}</skipTests></configuration>")
    }

    when:
    def exitCode = executeMaven(["-B", "clean", "test", skipArgument])

    then:
    exitCode == 0
    spanFilter.waitForSpan({ span -> span.spanType == "test_session_end" }, TimeUnit.SECONDS.toMillis(20))
    def spans = TEST_WRITER.toList().flatten()
    def modules = spans.findAll { it.spanType == "test_module_end" }
    def sessions = spans.findAll { it.spanType == "test_session_end" }
    modules.size() == 1
    sessions.size() == 1
    modules.every { it.getTag("test.status").toString() == expectedStatus }
    sessions.every { it.getTag("test.status").toString() == expectedStatus }
    expectedStatus != "skip" || modules.every { it.getTag("test.skip_reason") == "Tests were skipped by Maven configuration" }
    expectedStatus != "skip" || !spans.any { it.spanType == "test" || it.spanType == "test_suite_end" }

    where:
    testcaseName                                 | skipArgument             | pomSkipTests | expectedStatus
    "test_maven_build_with_tests_generates_spans"  | "-DskipTests"            | null         | "skip"
    "test_maven_build_with_tests_generates_spans"  | "-Dmaven.test.skip=true"  | null         | "skip"
    "test_maven_build_with_tests_generates_spans"  | "-DskipTests=false"      | null         | "pass"
    "test_maven_build_with_tests_generates_spans"  | "-DskipTests"            | "false"      | "pass"
    "test_maven_build_with_tests_generates_spans"  | "-DskipTests=false"      | "true"       | "skip"
  }

  def "Failsafe status follows skipITs: CLI=#cliSkipITs, POM=#pomSkipITs, unit tests skipped=#skipUnitTests"() {
    given:
    def pom = projectFolder.resolve("pom.xml").toFile()
    // An unrelated skipITs element must not mark Surefire tests as skipped.
    pom.text = pom.text.replace("<artifactId>maven-surefire-plugin</artifactId>",
      "<artifactId>maven-surefire-plugin</artifactId><configuration><skipTests>${skipUnitTests}</skipTests><skipITs>true</skipITs></configuration>")
    if (pomSkipITs != null) {
      pom.text = pom.text.replace("<artifactId>maven-failsafe-plugin</artifactId>",
        "<artifactId>maven-failsafe-plugin</artifactId><configuration><skipITs>${pomSkipITs}</skipITs></configuration>")
    }

    when:
    def exitCode = executeMaven(["-B", "clean", "verify", "-DskipITs=${cliSkipITs}".toString()])

    then:
    exitCode == 0
    spanFilter.waitForSpan({ span -> span.spanType == "test_session_end" }, TimeUnit.SECONDS.toMillis(20))
    def spans = TEST_WRITER.toList().flatten()
    def modules = spans.findAll { it.spanType == "test_module_end" }
    def sessions = spans.findAll { it.spanType == "test_session_end" }
    modules.size() == 2
    sessions.size() == 1
    def unitModule = modules.find { it.getTag("test.execution").toString().startsWith("maven-surefire-plugin:") }
    def integrationModule = modules.find { it.getTag("test.execution").toString().startsWith("maven-failsafe-plugin:") }
    unitModule.getTag("test.status").toString() == (skipUnitTests ? "skip" : "pass")
    integrationModule.getTag("test.status").toString() == expectedIntegrationStatus
    expectedIntegrationStatus != "skip" || integrationModule.getTag("test.skip_reason") == "Tests were skipped by Maven configuration"
    sessions[0].getTag("test.status").toString() == (skipUnitTests && expectedIntegrationStatus == "skip" ? "skip" : "pass")
    projectFolder.resolve("target/surefire-reports/TEST-org.example.TestSucceed.xml").toFile().exists() == !skipUnitTests
    projectFolder.resolve("target/failsafe-reports/TEST-org.example.ITSucceed.xml").toFile().exists() == (expectedIntegrationStatus == "pass")

    where:
    cliSkipITs | pomSkipITs | skipUnitTests | expectedIntegrationStatus
    true       | null       | false         | "skip"
    true       | null       | true          | "skip"
    false      | null       | false         | "pass"
    true       | "false"    | false         | "pass"
    false      | "true"     | false         | "skip"
    testcaseName = "test_maven_build_with_unit_and_integration_tests_generates_spans"
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
    for (int attempt = 0; attempt < DEPENDENCIES_DOWNLOAD_RETRIES; attempt++) {
      def exitCode = executeMaven(["org.apache.maven.plugins:maven-dependency-plugin:go-offline"])
      if (exitCode == 0) {
        return
      }
    }
    throw new AssertionError((Object) "Tried to download dependencies $DEPENDENCIES_DOWNLOAD_RETRIES times and failed")
  }

  private int executeMaven(List<String> args) {
    def arguments = new ArrayList<>(args)
    if (System.getenv("MAVEN_REPOSITORY_PROXY") != null) {
      def settingsFile = new File(getClass().getResource("/settings.mirror.xml").toURI())
      arguments.addAll(["-s", settingsFile.absolutePath])
    }
    return new MavenCli().doMain(arguments.toArray(new String[0]), projectFolder.toString(), null, null)
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
