package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import spock.lang.Shared
import spock.lang.Unroll

@Unroll
abstract class TestFrameworkTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.civisibility.enabled", "true")
  }

  void testSpan(TraceAssert trace, int index, final String testSuite, final String testName, final String testStatus, final Map<String, String> testTags = null, final Throwable exception = null, final boolean emptyDuration = false) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    trace.span {
      parent()
      operationName expectedOperationName()
      resourceName "$testSuite.$testName"
      spanType DDSpanTypes.TEST
      errored exception != null
      if(emptyDuration) {
        duration({it == 1L})
      } else {
        duration({it > 1L})
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_NAME" testName
        "$Tags.TEST_FRAMEWORK" testFramework
        if(testFrameworkVersion){
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        ciTags.each { key, val ->
          tag(key, val)
        }

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

  @Shared
  String component = component()

  @Shared
  boolean isCI = isCI()
  @Shared
  Map<String, String> ciTags = ciTags()

  abstract String expectedOperationName()

  abstract String expectedTestFramework()

  abstract String expectedTestFrameworkVersion()

  abstract String component()

  abstract boolean isCI()

  abstract Map<String, String> ciTags()
}
