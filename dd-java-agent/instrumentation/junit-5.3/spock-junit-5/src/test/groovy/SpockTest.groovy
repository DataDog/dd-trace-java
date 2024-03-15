import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.IsolatedClassLoader
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.TestFailedParameterizedSpock
import org.example.TestFailedSpock
import org.example.TestFailedThenSucceedParameterizedSpock
import org.example.TestFailedThenSucceedSpock
import org.example.TestParameterizedSetupSpecSpock
import org.example.TestParameterizedSpock
import org.example.TestSucceedAndFailedSpock
import org.example.TestSucceedSetupSpecSpock
import org.example.TestSucceedSpock
import org.example.TestSucceedSpockSlow
import org.example.TestSucceedSpockUnskippable
import org.example.TestSucceedSpockUnskippableSuite
import org.example.TestSucceedSpockVerySlow
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.spockframework.runtime.SpockEngine
import org.spockframework.util.SpockReleaseInfo

import java.util.stream.Collectors

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
    testcaseName                                     | tests                              | expectedTracesCount | skippedTests
    "test-itr-skipping"                              | [TestSucceedSpock]                 | 2                   | [new TestIdentifier("org.example.TestSucceedSpock", "test success", null, null)]
    "test-itr-skipping-parameterized"                | [TestParameterizedSpock]           | 3                   | [
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", '{"metadata":{"test_name":"test add 1 and 2"}}', null)
    ]
    "test-itr-unskippable"                           | [TestSucceedSpockUnskippable]      | 2                   | [new TestIdentifier("org.example.TestSucceedSpockUnskippable", "test success", null, null)]
    "test-itr-unskippable-suite"                     | [TestSucceedSpockUnskippableSuite] | 2                   | [new TestIdentifier("org.example.TestSucceedSpockUnskippableSuite", "test success", null, null)]
    "test-itr-skipping-spec-setup"                   | [TestSucceedSetupSpecSpock]        | 2                   | [
      new TestIdentifier("org.example.TestSucceedSetupSpecSpock", "test success", null, null),
      new TestIdentifier("org.example.TestSucceedSetupSpecSpock", "test another success", null, null)
    ]
    "test-itr-not-skipping-spec-setup"               | [TestSucceedSetupSpecSpock]        | 2                   | [new TestIdentifier("org.example.TestSucceedSetupSpecSpock", "test success", null, null)]
    "test-itr-not-skipping-parameterized-spec-setup" | [TestParameterizedSetupSpecSpock]  | 2                   | [
      new TestIdentifier("org.example.TestParameterizedSetupSpecSpock", "test add 1 and 2", '{"metadata":{"test_name":"test add 1 and 2"}}', null)
    ]
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

  def "test early flakiness detection #testcaseName"() {
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                        | tests                       | expectedTracesCount | knownTestsList
    "test-efd-known-test"               | [TestSucceedSpock]          | 2                   | [new TestIdentifier("org.example.TestSucceedSpock", "test success", null, null)]
    "test-efd-known-parameterized-test" | [TestParameterizedSpock]    | 3                   | [
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", null, null),
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 4 and 4", null, null)
    ]
    "test-efd-new-test"                 | [TestSucceedSpock]          | 4                   | []
    "test-efd-new-parameterized-test"   | [TestParameterizedSpock]    | 7                   | []
    "test-efd-known-tests-and-new-test" | [TestParameterizedSpock]    | 5                   | [new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", null, null)]
    "test-efd-new-slow-test"            | [TestSucceedSpockSlow]      | 3                   | [] // is executed only twice
    "test-efd-new-very-slow-test"       | [TestSucceedSpockVerySlow]  | 2                   | [] // is executed only once
    "test-efd-faulty-session-threshold" | [TestSucceedAndFailedSpock] | 8                   | []
  }

  private static void runTests(List<Class<?>> tests) {
    String[] testNames = tests.stream().map(Class::getName).collect(Collectors.toList()).toArray()

    IsolatedClassLoader.run(["org.junit", "org.spockframework", "org.example", "datadog.trace.test", "org.codehaus", "groovy.lang"], { String[] it ->
      TestEventsHandlerHolder.startForcefully()

      DiscoverySelector[] selectors = new DiscoverySelector[it.size()]
      for (i in 0..<it.size()) {
        selectors[i] = selectClass("org.example.TestSucceedSpock")
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

    }, testNames)
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
