import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.TestFailedParameterizedSpock
import org.example.TestFailedSpock
import org.example.TestFailedThenSucceedParameterizedSpock
import org.example.TestFailedThenSucceedSpock
import org.example.TestParameterizedSpock
import org.example.TestSucceedSpock
import org.example.TestSucceedSpockUnskippable
import org.example.TestSucceedSpockUnskippableSuite
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.spockframework.runtime.SpockEngine
import org.spockframework.util.SpockReleaseInfo

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class SpockTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    setup:
    runTests(tests)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                 | tests                    | expectedTracesCount
    "test-succeed"               | [TestSucceedSpock]       | 2
    "test-succeed-parameterized" | [TestParameterizedSpock] | 3
  }

  def "test ITR #testcaseName"() {
    setup:
    givenSkippableTests(skippedTests)
    runTests(tests)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                      | tests                              | expectedTracesCount | skippedTests
    "test-itr-skipping"               | [TestSucceedSpock]                 | 2                   | [new TestIdentifier("org.example.TestSucceedSpock", "test success", null, null)]
    "test-itr-skipping-parameterized" | [TestParameterizedSpock]           | 3                   | [
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", '{"metadata":{"test_name":"test add 1 and 2"}}', null)
    ]
    "test-itr-unskippable"            | [TestSucceedSpockUnskippable]      | 2                   | [new TestIdentifier("org.example.TestSucceedSpockUnskippable", "test success", null, null)]
    "test-itr-unskippable-suite"      | [TestSucceedSpockUnskippableSuite] | 2                   | [new TestIdentifier("org.example.TestSucceedSpockUnskippableSuite", "test success", null, null)]
  }

  def "test flaky retries #testcaseName"() {
    setup:
    givenFlakyTests(retriedTests)

    runTests(tests)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                             | tests                                     | expectedTracesCount | retriedTests
    "test-failed"                            | [TestFailedSpock]                         | 2                   | []
    "test-retry-failed"                      | [TestFailedSpock]                         | 6                   | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null, null)]
    "test-failed-then-succeed"               | [TestFailedThenSucceedSpock]              | 5                   | [
      new TestIdentifier("org.example.TestFailedThenSucceedSpock", "test failed then succeed", null, null)
    ]
    "test-retry-parameterized"               | [TestFailedParameterizedSpock]            | 3                   | [new TestIdentifier("org.example.TestFailedParameterizedSpock", "test add 4 and 4", null, null)]
    "test-parameterized-failed-then-succeed" | [TestFailedThenSucceedParameterizedSpock] | 5                   | [
      new TestIdentifier("org.example.TestFailedThenSucceedParameterizedSpock", "test add 1 and 2", null, null)
    ]
  }

  private static void runTests(List<Class<?>> classes) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[classes.size()]
    for (i in 0..<classes.size()) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new SpockEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)

    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "spock"
  }

  @Override
  String instrumentedLibraryVersion() {
    return SpockReleaseInfo.version
  }
}
