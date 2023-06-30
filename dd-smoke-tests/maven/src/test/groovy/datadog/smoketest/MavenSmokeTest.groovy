package datadog.smoketest

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.MultipartRequestParser
import datadog.trace.util.Strings
import org.apache.maven.wrapper.MavenWrapperMain
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static org.hamcrest.Matchers.emptyString
import static org.hamcrest.Matchers.not

class MavenSmokeTest extends Specification {

  private static final String TEST_SERVICE_NAME = "test-maven-service"
  private static final String TEST_ENVIRONMENT_NAME = "integration-test"

  private static final int PROCESS_TIMEOUT_SECS = 60

  private static final int DEPENDENCIES_DOWNLOAD_RETRIES = 5

  @TempDir
  Path projectHome

  @Shared
  ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory())

  @Shared
  Queue<Map<String, Object>> receivedTraces = new ConcurrentLinkedQueue<>()

  @Shared
  Queue<Map<String, Object>> receivedCoverages = new ConcurrentLinkedQueue<>()

  @Shared
  @AutoCleanup
  protected TestHttpServer intakeServer = httpServer {
    handlers {
      prefix("/api/v2/citestcycle") {
        def decodedEvent = objectMapper.readValue(request.body, Map)
        receivedTraces.add(decodedEvent)

        response.status(200).send()
      }

      prefix("/api/v2/citestcov") {
        def parsed = MultipartRequestParser.parseRequest(request.body, request.headers.get("Content-Type"))
        def coverages = parsed.get("coverage1")
        for (def coverage : coverages) {
          def decodedCoverage = objectMapper.readValue(coverage.get(), Map)
          receivedCoverages.add(decodedCoverage)
        }

        response.status(202).send()
      }
    }
  }

  def setup() {
    receivedTraces.clear()
    receivedCoverages.clear()
  }

  def "test successful maven run, v#mavenVersion"() {
    given:
    givenWrapperPropertiesFile(mavenVersion)
    givenMavenProjectFiles("test_sucessful_maven_run")
    givenMavenDependenciesAreLoaded()

    when:
    def exitCode = whenRunningMavenBuild()

    then:
    exitCode == 0

    def events = waitForEvents(4)
    assert events.size() == 4

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    verifyCommonTags(sessionEndEvent)
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "maven.test_session"
        resource == "Maven Smoke Tests Project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "maven:${mavenVersion}" // only applied to session events
        }
      }
    }

    def moduleEndEvent = events.find { it.type == "test_module_end" }
    assert moduleEndEvent != null
    verifyCommonTags(moduleEndEvent)
    verifyAll(moduleEndEvent) {
      verifyAll(content) {
        name == "maven.test_module"
        resource == "Maven Smoke Tests Project test" // project name + execution goal
        test_session_id == sessionEndEvent.content.test_session_id
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == "Maven Smoke Tests Project test" // project name + execution goal
        }
      }
    }

    def suiteEndEvent = events.find { it.type == "test_suite_end" }
    assert suiteEndEvent != null
    verifyCommonTags(suiteEndEvent, false)
    verifyAll(suiteEndEvent) {
      verifyAll(content) {
        name == "junit.test_suite"
        resource == "datadog.smoke.TestSucceed"
        test_session_id == sessionEndEvent.content.test_session_id
        test_module_id == moduleEndEvent.content.test_module_id
        test_suite_id > 0
        verifyAll(metrics) {
          process_id > 0
        }
        verifyAll(meta) {
          it["span.kind"] == "test_suite_end"
          it["test.suite"] == "datadog.smoke.TestSucceed"
          it["test.source.file"] == "src/test/java/datadog/smoke/TestSucceed.java"
          it["ci.provider.name"] == "jenkins"
        }
      }
    }

    def testEvent = events.find { it.type == "test" }
    verifyCommonTags(testEvent, false)
    verifyAll(testEvent) {
      verifyAll(content) {
        name == "junit.test"
        resource == "datadog.smoke.TestSucceed.test_succeed"
        test_session_id == sessionEndEvent.content.test_session_id
        test_module_id == moduleEndEvent.content.test_module_id
        test_suite_id == suiteEndEvent.content.test_suite_id
        trace_id > 0
        span_id > 0
        parent_id == 0
        verifyAll(metrics) {
          process_id > 0
        }
        verifyAll(meta) {
          it["span.kind"] == "test"
          it["test.suite"] == "datadog.smoke.TestSucceed"
          it["test.name"] == "test_succeed"
          it["test.source.file"] == "src/test/java/datadog/smoke/TestSucceed.java"
          it["ci.provider.name"] == "jenkins"
        }
      }
    }

    def coverages = waitForCoverages(1)
    assert coverages.size() == 1

    def coverage = coverages.first()
    coverage == [
      test_session_id: testEvent.content.test_session_id,
      test_suite_id  : testEvent.content.test_suite_id,
      span_id        : testEvent.content.span_id,
      files          : [
        [
          filename: "src/test/java/datadog/smoke/TestSucceed.java",
          segments: [[7, -1, 7, -1, -1], [11, -1, 12, -1, -1]]
        ]
      ]
    ]

    where:
    mavenVersion << ["3.2.1", "3.2.5", "3.3.9", "3.5.4", "3.6.3", "3.8.8", "3.9.3", "4.0.0-alpha-7"]
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
  }

  /**
   * Sometimes Maven has problems downloading project dependencies because of intermittent network issues.
   * Here, in order to reduce flakiness, we ensure that all of the dependencies are loaded (retrying if necessary),
   * before proceeding with running the build
   */
  private void givenMavenDependenciesAreLoaded() {
    def processBuilder = createProcessBuilder("dependency:go-offline", false)
    for (int attempt = 0; attempt < DEPENDENCIES_DOWNLOAD_RETRIES; attempt++) {
      def exitCode = runProcess(processBuilder.start())
      if (exitCode == 0) {
        return
      }
    }
    throw new AssertionError((Object) "Tried to download dependencies $DEPENDENCIES_DOWNLOAD_RETRIES times and failed")
  }

  private int whenRunningMavenBuild() {
    def processBuilder = createProcessBuilder("test")

    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF")
    // ensure CI provider is detected as Jenkins regardless of where the test is run
    processBuilder.environment().put("JENKINS_URL", "dummy")
    processBuilder.environment().put("WORKSPACE", projectHome.toAbsolutePath().toString())

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

  ProcessBuilder createProcessBuilder(String mvnCommand, boolean runWithAgent = true) {
    String mavenRunnerShadowJar = System.getProperty("datadog.smoketest.maven.jar.path")
    assert new File(mavenRunnerShadowJar).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(jvmArguments(runWithAgent))
    command.addAll((String[]) ["-jar", mavenRunnerShadowJar])
    command.addAll(programArguments())
    command.add(mvnCommand)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(projectHome.toFile())

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))

    return processBuilder
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  List<String> jvmArguments(boolean runWithAgent) {
    def arguments = [
      "-D${MavenWrapperMain.MVNW_VERBOSE}=true".toString(),
      "-Duser.dir=${projectHome.toAbsolutePath()}".toString(),
      "-Dmaven.multiModuleProjectDirectory=${projectHome.toAbsolutePath()}".toString(),
    ]
    if (runWithAgent) {
      def agentShadowJar = System.getProperty("datadog.smoketest.agent.shadowJar.path")
      def agentArgument = "-javaagent:${agentShadowJar}=" +
        "${Strings.propertyNameToSystemPropertyName(GeneralConfig.ENV)}=${TEST_ENVIRONMENT_NAME}," +
        "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED)}=true," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION)}=0.8.10," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_INCLUDES)}=datadog.smoke.*," +
        "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL)}=${intakeServer.address.toString()}"
      arguments += agentArgument.toString()
    }
    return arguments
  }

  List<String> programArguments() {
    return [projectHome.toAbsolutePath().toString()]
  }

  private List<Map<String, Object>> waitForEvents(expectedEventsSize = 4) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    traceReceiveConditions.eventually {
      int eventsSize = 0
      for (Map<String, Object> trace : receivedTraces) {
        eventsSize += trace["events"].size()
      }
      assert eventsSize == expectedEventsSize
    }

    List<Map<String, Object>> events = new ArrayList<>()
    while (!receivedTraces.isEmpty()) {
      def trace = receivedTraces.poll()
      events.addAll((List<Map<String, Object>>) trace["events"])
    }
    return events
  }

  private List<Map<String, Object>> waitForCoverages(traceSize = 1) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    traceReceiveConditions.eventually {
      assert receivedCoverages.size() == traceSize
    }

    List<Map<String, Object>> coverages = new ArrayList<>()
    while (!receivedCoverages.isEmpty()) {
      def trace = receivedCoverages.poll()
      coverages.addAll((List<Map<String, Object>>) trace["coverages"])
    }
    return coverages
  }

  protected verifyCommonTags(Map<String, Object> event, boolean buildEvent = true) {
    verifyAll(event) {
      version > 0
      verifyAll(content) {
        start > 0
        duration > 0
        test_session_id > 0
        service == TEST_SERVICE_NAME
        error == 0
        verifyAll(meta) {
          it["test.type"] == "test"
          it["test.status"] == "pass"

          if (buildEvent) {
            // Maven 4 sets "-B" flag ("batch", non-interactive mode)
            it["test.command"] == "mvn test" || it["test.command"] == "mvn -B test"
            it["component"] == "maven"
          } else {
            // testcase/suite event
            it["component"] == "junit"
          }

          it["test.framework"] == "junit4"
          it["test.framework_version"] == "4.13.2"

          it["env"] == TEST_ENVIRONMENT_NAME
          it["runtime.name"] not(emptyString())
          it["runtime.vendor"] not(emptyString())
          it["runtime.version"] not(emptyString())
          it["runtime-id"] not(emptyString())
          it["os.platform"] not(emptyString())
          it["os.version"] not(emptyString())
          it["library_version"] not(emptyString())
        }
      }
    }
    return true
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

}
