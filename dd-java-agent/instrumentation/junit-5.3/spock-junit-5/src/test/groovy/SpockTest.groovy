import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
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
    givenSkippableTests(skippedTests)
    runTests(tests)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                      | tests                              | expectedTracesCount | skippedTests
    "test-succeed"                    | [TestSucceedSpock]                 | 2                   | []
    "test-succeed-parameterized"      | [TestParameterizedSpock]           | 3                   | []
    "test-itr-skipping"               | [TestSucceedSpock]                 | 2                   | [new SkippableTest("org.example.TestSucceedSpock", "test success", null, null)]
    "test-itr-skipping-parameterized" | [TestParameterizedSpock]           | 3                   | [
      new SkippableTest("org.example.TestParameterizedSpock", "test add 1 and 2", '{"metadata":{"test_name":"test add 1 and 2"}}', null)
    ]
    "test-itr-unskippable"            | [TestSucceedSpockUnskippable]      | 2                   | [new SkippableTest("org.example.TestSucceedSpockUnskippable", "test success", null, null)]
    "test-itr-unskippable-suite"      | [TestSucceedSpockUnskippableSuite] | 2                   | [new SkippableTest("org.example.TestSucceedSpockUnskippableSuite", "test success", null, null)]
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
