package datadog.smoketest

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.util.Strings
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions
import spock.util.environment.Jvm

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static org.hamcrest.Matchers.emptyString
import static org.hamcrest.Matchers.not

@Unroll
class GradleDaemonSmokeTest extends Specification {

  private static final String TEST_SERVICE_NAME = "test-gradle-service"
  private static final String TEST_ENVIRONMENT_NAME = "integration-test"

  // test resources use this instead of ".gradle" to avoid unwanted evaluation
  private static final String GRADLE_TEST_RESOURCE_EXTENSION = ".gradleTest"
  private static final String GRADLE_REGULAR_EXTENSION = ".gradle"

  // TODO: Gradle daemons started by the TestKit have an idle period of 3 minutes
  //  so by the time tests finish, at least some of the daemons are still alive.
  //  Because of that the temporary TestKit folder cannot be fully deleted
  @Shared
  @TempDir
  Path testKitFolder

  @TempDir
  Path projectFolder

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

  def setupSpec() {
    givenGradleProperties()
  }

  def setup() {
    receivedTraces.clear()
  }

  def cleanup() {
    receivedTraces.clear()
  }

  def "Successful build emits session and module spans: Gradle v#gradleVersion"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("datadog/smoketest/success/")

    when:
    BuildResult buildResult = runGradleTests(gradleVersion)

    then:
    assertBuildSuccessful(buildResult)

    def events = waitForEvents(2)
    assert events.size() == 4

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    assert sessionEndEvent != null
    verifyCommonTags(sessionEndEvent)
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "gradle.test_session"
        resource == "gradle-instrumentation-test-project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "gradle:${gradleVersion}" // only applied to session events
        }
      }
    }

    def moduleEndEvent = events.find { it.type == "test_module_end" }
    assert moduleEndEvent != null
    verifyCommonTags(moduleEndEvent)
    verifyAll(moduleEndEvent) {
      verifyAll(content) {
        name == "gradle.test_module"
        resource == ":test" // task path
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == ":test" // task path
        }
      }
    }

    def suiteEndEvent = events.find { it.type == "test_suite_end" }
    assert suiteEndEvent != null
    verifyCommonTags(suiteEndEvent, "pass", true, false)
    verifyAll(suiteEndEvent) {
      verifyAll(content) {
        name == "junit.test_suite"
        resource == "datadog.smoke.TestSucceed"
        test_module_id > 0
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
    assert testEvent != null
    verifyCommonTags(testEvent, "pass", true, false)
    verifyAll(testEvent) {
      verifyAll(content) {
        name == "junit.test"
        resource == "datadog.smoke.TestSucceed.test_succeed"
        test_module_id > 0
        test_suite_id > 0
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
          segments: [[11, -1, 12, -1, -1]]
        ]
      ]
    ]

    where:
    gradleVersion << ["4.0", "5.0", "6.0", "7.0", "7.6.1", "8.0.2", "8.1.1"]
  }

  // this is a separate test case since older Gradle versions need to declare dependencies differently
  // (`testImplementation` is not supported, and `compile` should be used instead)
  def "Successful legacy project build emits session and module spans: Gradle v#gradleVersion"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("datadog/smoketest/successLegacy/")

    when:
    BuildResult buildResult = runGradleTests(gradleVersion)

    then:
    assertBuildSuccessful(buildResult)

    def events = waitForEvents(2)
    assert events.size() == 4

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    assert sessionEndEvent != null
    verifyCommonTags(sessionEndEvent)
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "gradle.test_session"
        resource == "gradle-instrumentation-test-project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "gradle:${gradleVersion}" // only applied to session events
        }
      }
    }

    def moduleEndEvent = events.find { it.type == "test_module_end" }
    assert moduleEndEvent != null
    verifyCommonTags(moduleEndEvent)
    verifyAll(moduleEndEvent) {
      verifyAll(content) {
        name == "gradle.test_module"
        resource == ":test" // task path
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == ":test" // task path
        }
      }
    }

    def suiteEndEvent = events.find { it.type == "test_suite_end" }
    assert suiteEndEvent != null
    verifyCommonTags(suiteEndEvent, "pass", true, false)
    verifyAll(suiteEndEvent) {
      verifyAll(content) {
        name == "junit.test_suite"
        resource == "datadog.smoke.TestSucceed"
        test_module_id > 0
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
    assert testEvent != null
    verifyCommonTags(testEvent, "pass", true, false)
    verifyAll(testEvent) {
      verifyAll(content) {
        name == "junit.test"
        resource == "datadog.smoke.TestSucceed.test_succeed"
        test_module_id > 0
        test_suite_id > 0
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
          segments: [[11, -1, 12, -1, -1]]
        ]
      ]
    ]

    where:
    // Gradle TestKit supports versions 2.6 and later
    gradleVersion << ["2.6", "3.0"]
  }

  def "Successful multi-module build emits multiple module spans: Gradle v#gradleVersion"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("datadog/smoketest/successMultiModule/")

    when:
    BuildResult buildResult = runGradleTests(gradleVersion)

    then:
    assertBuildSuccessful(buildResult)

    def events = waitForEvents(3)
    assert events.size() == 7

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    assert sessionEndEvent != null
    verifyCommonTags(sessionEndEvent)
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "gradle.test_session"
        resource == "gradle-instrumentation-test-project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "gradle:${gradleVersion}" // only applied to session events
        }
      }
    }

    def moduleAEndEvent = events.find { it.type == "test_module_end" && it.content.resource == ":submodule-a:test" }
    assert moduleAEndEvent != null
    verifyCommonTags(moduleAEndEvent)
    verifyAll(moduleAEndEvent) {
      verifyAll(content) {
        name == "gradle.test_module"
        resource == ":submodule-a:test" // task path
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == ":submodule-a:test" // task path
        }
      }
    }

    def moduleBEndEvent = events.find { it.type == "test_module_end" && it.content.resource == ":submodule-b:test" }
    assert moduleBEndEvent != null
    verifyCommonTags(moduleBEndEvent)
    verifyAll(moduleBEndEvent) {
      verifyAll(content) {
        name == "gradle.test_module"
        resource == ":submodule-b:test" // task path
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == ":submodule-b:test" // task path
        }
      }
    }

    def suiteAEndEvent = events.find { it.type == "test_suite_end" && it.content.meta["ci.workspace_path"]?.contains("submodule-a") }
    assert suiteAEndEvent != null
    verifyCommonTags(suiteAEndEvent, "pass", true, false)
    verifyAll(suiteAEndEvent) {
      verifyAll(content) {
        name == "junit.test_suite"
        resource == "datadog.smoke.TestSucceed"
        test_module_id > 0
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

    def testAEvent = events.find { it.type == "test" && it?.content?.meta["ci.workspace_path"]?.contains("submodule-a") }
    assert testAEvent != null
    verifyCommonTags(testAEvent, "pass", true, false)
    verifyAll(testAEvent) {
      verifyAll(content) {
        name == "junit.test"
        resource == "datadog.smoke.TestSucceed.test_succeed"
        test_module_id > 0
        test_suite_id > 0
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

    def suiteBEndEvent = events.find { it.type == "test_suite_end" && it.content.meta["ci.workspace_path"]?.contains("submodule-b") }
    assert suiteBEndEvent != null
    verifyCommonTags(suiteBEndEvent, "pass", true, false)
    verifyAll(suiteBEndEvent) {
      verifyAll(content) {
        name == "junit.test_suite"
        resource == "datadog.smoke.TestSucceed"
        test_module_id > 0
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

    def testBEvent = events.find { it.type == "test" && it?.content?.meta["ci.workspace_path"]?.contains("submodule-b") }
    assert testBEvent != null
    verifyCommonTags(testBEvent, "pass", true, false)
    verifyAll(testBEvent) {
      verifyAll(content) {
        name == "junit.test"
        resource == "datadog.smoke.TestSucceed.test_succeed"
        test_module_id > 0
        test_suite_id > 0
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

    where:
    //
    gradleVersion << ["4.0", "5.0", "6.0", "7.0", "7.6.1", "8.0.2", "8.1.1"]
  }

  def "Failed build emits session and module spans: Gradle v#gradleVersion"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("datadog/smoketest/failure/")

    when:
    BuildResult buildResult = runGradleTests(gradleVersion, false)

    then:
    assert buildResult.tasks != null
    assert buildResult.tasks.size() > 0
    for (def task : buildResult.tasks) {
      if (task.path != ":test") {
        assert task.outcome != TaskOutcome.FAILED
      } else {
        assert task.outcome == TaskOutcome.FAILED
      }
    }

    def events = waitForEvents(2)
    assert events.size() == 4

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    assert sessionEndEvent != null
    verifyCommonTags(sessionEndEvent, "fail")
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "gradle.test_session"
        resource == "gradle-instrumentation-test-project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "gradle:${gradleVersion}" // only applied to session events
        }
      }
    }

    def moduleEndEvent = events.find { it.type == "test_module_end" }
    assert moduleEndEvent != null
    verifyCommonTags(moduleEndEvent, "fail")
    verifyAll(moduleEndEvent) {
      verifyAll(content) {
        name == "gradle.test_module"
        resource == ":test" // task path
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == ":test" // task path
        }
      }
    }

    def suiteEndEvent = events.find { it.type == "test_suite_end" }
    assert suiteEndEvent != null
    verifyCommonTags(suiteEndEvent, "fail", true, false)
    verifyAll(suiteEndEvent) {
      verifyAll(content) {
        name == "junit.test_suite"
        resource == "datadog.smoke.TestFailed"
        test_module_id > 0
        test_suite_id > 0
        verifyAll(metrics) {
          process_id > 0
        }
        verifyAll(meta) {
          it["span.kind"] == "test_suite_end"
          it["test.suite"] == "datadog.smoke.TestFailed"
          it["test.source.file"] == "src/test/java/datadog/smoke/TestFailed.java"
          it["ci.provider.name"] == "jenkins"
        }
      }
    }

    def testEvent = events.find { it.type == "test" }
    assert testEvent != null
    verifyCommonTags(testEvent, "fail", true, false)
    verifyAll(testEvent) {
      verifyAll(content) {
        name == "junit.test"
        resource == "datadog.smoke.TestFailed.test_failed"
        test_module_id > 0
        test_suite_id > 0
        trace_id > 0
        span_id > 0
        parent_id == 0
        verifyAll(metrics) {
          process_id > 0
        }
        verifyAll(meta) {
          it["span.kind"] == "test"
          it["test.suite"] == "datadog.smoke.TestFailed"
          it["test.name"] == "test_failed"
          it["test.source.file"] == "src/test/java/datadog/smoke/TestFailed.java"
          it["ci.provider.name"] == "jenkins"
        }
      }
    }

    where:
    gradleVersion << ["4.0", "5.0", "6.0", "7.0", "7.6.1", "8.0.2", "8.1.1"]
  }

  def "Build without tests emits session and module spans: Gradle v#gradleVersion"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("datadog/smoketest/skip/")

    when:
    BuildResult buildResult = runGradleTests(gradleVersion)

    then:
    assertBuildSuccessful(buildResult)

    def events = waitForEvents()
    assert events.size() == 2

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    assert sessionEndEvent != null
    verifyCommonTags(sessionEndEvent, "skip")
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "gradle.test_session"
        resource == "gradle-instrumentation-test-project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "gradle:${gradleVersion}" // only applied to session events
        }
      }
    }

    def moduleEndEvent = events.find { it.type == "test_module_end" }
    assert moduleEndEvent != null
    verifyCommonTags(moduleEndEvent, "skip")
    verifyAll(moduleEndEvent) {
      verifyAll(content) {
        name == "gradle.test_module"
        resource == ":test" // task path
        test_module_id > 0
        verifyAll(meta) {
          it["span.kind"] == "test_module_end"
          it["test.module"] == ":test" // task path
          it["test.skip_reason"] == "NO-SOURCE"
        }
      }
    }

    where:
    gradleVersion << ["4.0", "5.0", "6.0", "7.0", "7.6.1", "8.0.2", "8.1.1"]
  }

  def "Corrupted build emits session span: Gradle v#gradleVersion"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("datadog/smoketest/corruptedConfig/")

    when:
    BuildResult buildResult = runGradleTests(gradleVersion, false)

    then:
    assert buildResult.tasks != null
    assert buildResult.tasks.size() == 0

    def events = waitForEvents()
    assert events.size() == 1

    def sessionEndEvent = events.find { it.type == "test_session_end" }
    assert sessionEndEvent != null
    verifyCommonTags(sessionEndEvent, "fail", false)
    verifyAll(sessionEndEvent) {
      verifyAll(content) {
        name == "gradle.test_session"
        resource == "gradle-instrumentation-test-project" // project name
        verifyAll(metrics) {
          process_id > 0 // only applied to root spans
        }
        verifyAll(meta) {
          it["span.kind"] == "test_session_end"
          it["language"] == "jvm" // only applied to root spans
          it["test.toolchain"] == "gradle:${gradleVersion}" // only applied to session events
        }
      }
    }

    where:
    gradleVersion << ["4.0", "5.0", "6.0", "7.0", "7.6.1", "8.0.2", "8.1.1"]
  }

  private void givenGradleProperties() {
    String agentShadowJar = System.getProperty("datadog.smoketest.agent.shadowJar.path")
    assert new File(agentShadowJar).isFile()

    def ddApiKeyPath = testKitFolder.resolve(".dd.api.key")
    Files.write(ddApiKeyPath, "dummy".getBytes())

    def gradleProperties =
      "org.gradle.jvmargs=" +
      "-javaagent:${agentShadowJar}=" +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.ENV)}=${TEST_ENVIRONMENT_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.SERVICE_NAME)}=${TEST_SERVICE_NAME}," +
      "${Strings.propertyNameToSystemPropertyName(GeneralConfig.API_KEY_FILE)}=${ddApiKeyPath.toAbsolutePath().toString()}," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_PER_TEST_CODE_COVERAGE_ENABLED)}=true," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_VERSION)}=0.8.10," +
      "${Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_JACOCO_PLUGIN_INCLUDES)}=datadog.smoke.*," +
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
  }

  private BuildResult runGradleTests(String gradleVersion, boolean successExpected = true) {
    def arguments = ["test", "--stacktrace"]
    if (gradleVersion > "5.6") {
      // fail on warnings is available starting from Gradle 5.6
      arguments += ["--warning-mode", "fail"]
    } else if (gradleVersion > "4.5") {
      // warning mode available starting from Gradle 4.5
      arguments += ["--warning-mode", "all"]
    }
    GradleRunner gradleRunner = GradleRunner.create()
      .withTestKitDir(testKitFolder.toFile())
      .withProjectDir(projectFolder.toFile())
      .withGradleVersion(gradleVersion)
      .withArguments(arguments)
    def buildResult = successExpected ? gradleRunner.build() : gradleRunner.buildAndFail()
    println buildResult.output
    buildResult
  }

  private void assertBuildSuccessful(buildResult) {
    assert buildResult.tasks != null
    assert buildResult.tasks.size() > 0
    for (def task : buildResult.tasks) {
      assert task.outcome != TaskOutcome.FAILED
    }
  }

  private List<Map<String, Object>> waitForEvents(traceSize = 1) {
    def traceReceiveConditions = new PollingConditions(timeout: 15, initialDelay: 1, delay: 0.5, factor: 1)
    traceReceiveConditions.eventually {
      assert receivedTraces.size() == traceSize
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

  protected verifyCommonTags(Map<String, Object> event, String status = "pass", boolean parsingSuccessful = true, boolean buildEvent = true) {
    verifyAll(event) {
      version > 0
      verifyAll(content) {
        start > 0
        duration > 0
        test_session_id > 0
        service == TEST_SERVICE_NAME
        error == (status != "fail") ? 0 : 1
        verifyAll(meta) {
          it["test.type"] == "test"
          it["test.status"] == status

          if (buildEvent) {
            it["test.command"] == "gradle test"
            it["component"] == "gradle"
          } else {
            // testcase/suite event
            it["component"] == "junit"
          }

          // if project files could not be parsed, we cannot know which test framework is used
          if (parsingSuccessful) {
            it["test.framework"] == "junit4"
            it["test.framework_version"] == "4.10"
          }

          it["env"] == TEST_ENVIRONMENT_NAME
          it["runtime.name"] not(emptyString())
          it["runtime.vendor"] not(emptyString())
          it["runtime.version"] not(emptyString())
          it["runtime-id"] not(emptyString())
          it["os.platform"] not(emptyString())
          it["os.version"] not(emptyString())
          it["library_version"] not(emptyString())

          if (status == "fail") {
            it["error.type"] not(emptyString())
            it["error.message"] not(emptyString())
            it["error.stack"] not(emptyString())
          }
        }
      }
    }
    return true
  }

  void givenGradleVersionIsCompatibleWithCurrentJvm(String gradleVersion) {
    Assumptions.assumeTrue(isSupported(gradleVersion),
      "Current JVM " + Jvm.current.javaVersion + " does not support Gradle version " + gradleVersion)
  }

  private static boolean isSupported(String gradleVersion) {
    // https://docs.gradle.org/current/userguide/compatibility.html
    if (Jvm.current.java20Compatible) {
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

}
