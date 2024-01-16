package datadog.smoketest


import datadog.trace.api.Config
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.civisibility.CiVisibilitySmokeTest
import datadog.trace.util.Strings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.maven.wrapper.MavenWrapperMain
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import spock.lang.TempDir

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

  def "test #projectName, v#mavenVersion"() {
    givenWrapperPropertiesFile(mavenVersion)
    givenMavenProjectFiles(projectName)
    givenMavenDependenciesAreLoaded(projectName, mavenVersion)
    givenFlakyRetries(flakyRetries)

    def exitCode = whenRunningMavenBuild(jacocoCoverage)

    if (expectSuccess) {
      assert exitCode == 0
    } else {
      assert exitCode != 0
    }
    verifyEventsAndCoverages(projectName, "maven", mavenVersion, expectedEvents, expectedCoverages)

    where:
    projectName                                         | mavenVersion         | expectedEvents | expectedCoverages | expectSuccess | flakyRetries | jacocoCoverage
    "test_successful_maven_run"                         | "3.2.1"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run"                         | "3.5.4"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run"                         | "3.6.3"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run"                         | "3.8.8"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run"                         | "3.9.5"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run_surefire_3_0_0"          | "3.9.5"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run_surefire_3_0_0"          | LATEST_MAVEN_VERSION | 5              | 1                 | true          | false        | true
    "test_successful_maven_run_builtin_coverage"        | "3.9.5"              | 5              | 1                 | true          | false        | false
    "test_successful_maven_run_with_jacoco_and_argline" | "3.9.5"              | 5              | 1                 | true          | false        | true
    "test_successful_maven_run_with_cucumber"           | "3.9.5"              | 7              | 1                 | true          | false        | true
    "test_failed_maven_run_flaky_retries"               | "3.9.5"              | 8              | 1                 | false         | true         | true
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
  private void givenMavenDependenciesAreLoaded(String projectName, String mavenVersion) {
    if (LOADED_DEPENDENCIES.add("$projectName:$mavenVersion")) {
      retryUntilSuccessfulOrNoAttemptsLeft(["dependency:go-offline"])
    }
    // dependencies below are download separately
    // because they are not declared in the project,
    // but are added at runtime by the tracer
    if (LOADED_DEPENDENCIES.add("com.datadoghq:dd-javac-plugin:$JAVAC_PLUGIN_VERSION")) {
      retryUntilSuccessfulOrNoAttemptsLeft(["dependency:get", "-Dartifact=com.datadoghq:dd-javac-plugin:$JAVAC_PLUGIN_VERSION".toString()])
    }
    if (LOADED_DEPENDENCIES.add("org.jacoco:jacoco-maven-plugin:$JACOCO_PLUGIN_VERSION")) {
      retryUntilSuccessfulOrNoAttemptsLeft(["dependency:get", "-Dartifact=org.jacoco:jacoco-maven-plugin:$JACOCO_PLUGIN_VERSION".toString()])
    }
  }

  private static final Collection<String> LOADED_DEPENDENCIES = new HashSet<>()

  private void retryUntilSuccessfulOrNoAttemptsLeft(List<String> mvnCommand) {
    def processBuilder = createProcessBuilder(mvnCommand, false, false)
    for (int attempt = 0; attempt < DEPENDENCIES_DOWNLOAD_RETRIES; attempt++) {
      def exitCode = runProcess(processBuilder.start())
      if (exitCode == 0) {
        return
      }
    }
    throw new AssertionError((Object) "Tried $DEPENDENCIES_DOWNLOAD_RETRIES times to execute $mvnCommand and failed")
  }

  private int whenRunningMavenBuild(boolean injectJacoco) {
    def processBuilder = createProcessBuilder(["-B", "test"], true, injectJacoco)

    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF")

    return runProcess(processBuilder.start())
  }

  private runProcess(Process p) {
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

  ProcessBuilder createProcessBuilder(List<String> mvnCommand, boolean runWithAgent, boolean injectJacoco) {
    String mavenRunnerShadowJar = System.getProperty("datadog.smoketest.maven.jar.path")
    assert new File(mavenRunnerShadowJar).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(jvmArguments(runWithAgent, injectJacoco))
    command.addAll((String[]) ["-jar", mavenRunnerShadowJar])
    command.addAll(programArguments())
    command.addAll(mvnCommand)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(projectHome.toFile())

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))

    return processBuilder
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  List<String> jvmArguments(boolean runWithAgent, boolean injectJacoco) {
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
        "${Strings.propertyNameToSystemPropertyName(GeneralConfig.ENV)}=${TEST_ENVIRONMENT_NAME}," +
        "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED)}=false," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_SOURCE_DATA_ROOT_CHECK_ENABLED)}=false," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED)}=false," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_VERSION)}=${JAVAC_PLUGIN_VERSION}," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL)}=${intakeServer.address.toString()},"

      if (injectJacoco) {
        agentArgument += "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_SEGMENTS_ENABLED)}=true," +
          "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION)}=${JACOCO_PLUGIN_VERSION},"
      }

      arguments += agentArgument.toString()
    }
    return arguments
  }

  List<String> programArguments() {
    return [projectHome.toAbsolutePath().toString()]
  }

  void givenFlakyRetries(boolean flakyRetries) {
    this.flakyRetriesEnabled = flakyRetries
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
          if (!version.contains('alpha')) {
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
    def hardcodedLatestVersion = "4.0.0-alpha-12" // latest alpha that is known to work
    LOGGER.info("Will run the 'latest' tests with hard-coded version ${hardcodedLatestVersion}")
    return hardcodedLatestVersion
  }
}
