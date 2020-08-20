package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared
import spock.lang.Unroll

@Unroll
abstract class TestFrameworkTest extends AgentTestRunner {

  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.integration.junit.enabled", "true")
      System.setProperty("dd.integration.testng.enabled", "true")
    }
  }

  void testSpan(TraceAssert trace, int index, final String testSuite, final String testName, final String testStatus, final Throwable exception = null) {
    def testFramework = expectedTestFramework()

    trace.span(index) {
      parent()
      operationName expectedOperationName()
      resourceName "$testSuite.$testName"
      spanType DDSpanTypes.TEST
      errored exception != null
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST
        "$DDTags.TEST_SUITE" testSuite
        "$DDTags.TEST_NAME" testName
        "$DDTags.TEST_FRAMEWORK" testFramework
        "$DDTags.TEST_STATUS" testStatus
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        defaultTags()
      }
    }
  }

  @Shared
  String component = component()

  abstract String expectedOperationName()

  abstract String expectedTestFramework()

  abstract String component()
}
