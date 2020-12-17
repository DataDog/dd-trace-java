package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator
import spock.lang.Shared
import spock.lang.Unroll

@Unroll
abstract class TestFrameworkTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.junit.enabled", "true")
    injectSysConfig("dd.integration.testng.enabled", "true")
  }

  void testSpan(TraceAssert trace, int index, final String testSuite, final String testName, final String testStatus, final Map<String, String> testTags = null, final Throwable exception = null) {
    def testFramework = expectedTestFramework()

    trace.span {
      parent()
      operationName expectedOperationName()
      resourceName "$testSuite.$testName"
      spanType DDSpanTypes.TEST
      errored exception != null
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_NAME" testName
        "$Tags.TEST_FRAMEWORK" testFramework
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        if (isCI) {
          ciTags.each {
            key, val -> tag(key, val)
          }
        }

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

  abstract String component()

  abstract boolean isCI()

  abstract Map<String, String> ciTags()

}
