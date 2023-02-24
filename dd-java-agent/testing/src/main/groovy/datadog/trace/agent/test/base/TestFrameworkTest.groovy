package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.codeowners.Codeowners
import datadog.trace.api.civisibility.source.MethodLinesResolver
import datadog.trace.api.civisibility.source.SourcePathResolver
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import datadog.trace.util.Strings
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path

@Unroll
abstract class TestFrameworkTest extends AgentTestRunner {

  static final String DUMMY_MODULE = "dummy_module"
  static final String DUMMY_CI_TAG = "dummy_ci_tag"
  static final String DUMMY_CI_TAG_VALUE = "dummy_ci_tag_value"
  static final String DUMMY_SOURCE_PATH = "dummy_source_path"
  static final int DUMMY_TEST_METHOD_START = 12
  static final int DUMMY_TEST_METHOD_END = 18
  static final Collection<String> DUMMY_CODE_OWNERS = ["owner1", "owner2"]

  private static Path agentKeyFile

  def setupSpec() {
    InstrumentationBridge.ci = true
    InstrumentationBridge.ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]

    InstrumentationBridge.sourcePathResolver = Stub(SourcePathResolver)
    InstrumentationBridge.sourcePathResolver.getSourcePath(_) >> DUMMY_SOURCE_PATH

    InstrumentationBridge.methodLinesResolver = Stub(MethodLinesResolver)
    InstrumentationBridge.methodLinesResolver.getLines(_) >> new MethodLinesResolver.MethodLines(DUMMY_TEST_METHOD_START, DUMMY_TEST_METHOD_END)

    InstrumentationBridge.codeowners = Stub(Codeowners)
    InstrumentationBridge.codeowners.getOwners(DUMMY_SOURCE_PATH) >> DUMMY_CODE_OWNERS

    InstrumentationBridge.module = DUMMY_MODULE
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    agentKeyFile = Files.createTempFile("TestFrameworkTest", "dummy_agent_key")
    Files.write(agentKeyFile, "dummy".getBytes())

    injectSysConfig(GeneralConfig.API_KEY_FILE, agentKeyFile.toString())
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, "true")
  }

  def cleanupSpec() {
    Files.deleteIfExists(agentKeyFile)
  }

  Long testModuleSpan(final TraceAssert trace,
    final int index,
    final String testStatus,
    final Map<String, String> testTags = null,
    final Throwable exception = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testModuleId
    trace.span(index) {
      testModuleId = span.getTag(Tags.TEST_MODULE_ID)

      parent()
      operationName expectedOperationPrefix() + ".test_module"
      resourceName DUMMY_MODULE
      spanType DDSpanTypes.TEST_MODULE_END
      errored exception != null
      duration({ it > 1L })
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST_MODULE
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_MODULE" DUMMY_MODULE
        "$Tags.TEST_BUNDLE" DUMMY_MODULE
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        InstrumentationBridge.ciTags.each { key, val ->
          tag(key, val)
        }

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        "$Tags.TEST_MODULE_ID" Long

        defaultTags()
      }
    }
    return testModuleId
  }

  Long testSuiteSpan(final TraceAssert trace,
    final int index,
    final Long parentId,
    final Long testModuleId,
    final String testSuite,
    final String testStatus,
    final Map<String, String> testTags = null,
    final Throwable exception = null,
    final boolean emptyDuration = false,
    final Collection<String> categories = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testSuiteId
    trace.span(index) {
      testSuiteId = span.getTag(Tags.TEST_SUITE_ID)

      parentSpanId(BigInteger.valueOf(parentId))
      operationName expectedOperationPrefix() + ".test_suite"
      resourceName testSuite
      spanType DDSpanTypes.TEST_SUITE_END
      errored exception != null
      if (emptyDuration) {
        duration({ it == 1L })
      } else {
        duration({ it > 1L })
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST_SUITE
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_MODULE_ID" testModuleId
        "$Tags.TEST_MODULE" DUMMY_MODULE
        "$Tags.TEST_BUNDLE" DUMMY_MODULE
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }
        "$Tags.TEST_SOURCE_FILE" DUMMY_SOURCE_PATH

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        if (categories) {
          "$Tags.TEST_TRAITS" Strings.toJson(["category": Strings.toJson(categories)], true)
        }

        InstrumentationBridge.ciTags.each { key, val ->
          tag(key, val)
        }

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        "$Tags.TEST_SUITE_ID" Long

        defaultTags()
      }
    }
    return testSuiteId
  }

  void testSpan(final TraceAssert trace,
    final int index,
    final Long testModuleId,
    final Long testSuiteId,
    final String testSuite,
    final String testName,
    final String testStatus,
    final Map<String, String> testTags = null,
    final Throwable exception = null,
    final boolean emptyDuration = false,
    final Collection<String> categories = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    trace.span(index) {
      if (testSuiteId != null) {
        parentSpanId(BigInteger.valueOf(testSuiteId))
      } else {
        parent()
      }
      operationName expectedOperationPrefix() + ".test"
      resourceName "$testSuite.$testName"
      spanType DDSpanTypes.TEST
      errored exception != null
      if (emptyDuration) {
        duration({ it == 1L })
      } else {
        duration({ it > 1L })
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        if (testModuleId != null) {
          "$Tags.TEST_MODULE_ID" testModuleId
        }
        if (testSuiteId != null) {
          "$Tags.TEST_SUITE_ID" testSuiteId
        }
        "$Tags.TEST_MODULE" DUMMY_MODULE
        "$Tags.TEST_BUNDLE" DUMMY_MODULE
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_NAME" testName
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }
        "$Tags.TEST_SOURCE_FILE" DUMMY_SOURCE_PATH
        "$Tags.TEST_SOURCE_START" DUMMY_TEST_METHOD_START
        "$Tags.TEST_SOURCE_END" DUMMY_TEST_METHOD_END
        "$Tags.TEST_CODEOWNERS" Strings.toJson(DUMMY_CODE_OWNERS)

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        if (categories) {
          "$Tags.TEST_TRAITS" Strings.toJson(["category": Strings.toJson(categories)], true)
        }

        InstrumentationBridge.ciTags.each { key, val ->
          tag(key, val)
        }

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        defaultTags()
      }
    }
  }

  String component = component()

  abstract String expectedOperationPrefix()

  abstract String expectedTestFramework()

  abstract String expectedTestFrameworkVersion()

  abstract String component()
}
