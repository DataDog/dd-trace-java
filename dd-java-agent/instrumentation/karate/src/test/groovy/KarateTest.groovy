import com.intuit.karate.FileUtils
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.karate.TestEventsHandlerHolder
import org.example.TestFailedKarate
import org.example.TestParameterizedKarate
import org.example.TestSucceedKarate
import org.example.TestUnskippableKarate
import org.example.TestWithSetupKarate
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class KarateTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    setup:
    Assumptions.assumeTrue(assumption)

    givenSkippableTests(skippedTests)

    runTests(tests)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                      | tests                     | expectedTracesCount | skippedTests                                                                                                                                           | assumption
    "test-succeed"                    | [TestSucceedKarate]       | 3                   | []                                                                                                                                                     | true
    "test-with-setup"                 | [TestWithSetupKarate]     | 3                   | []                                                                                                                                                     | isSetupTagSupported(FileUtils.KARATE_VERSION)
    "test-parameterized"              | [TestParameterizedKarate] | 3                   | []                                                                                                                                                     | true
    "test-failed"                     | [TestFailedKarate]        | 3                   | []                                                                                                                                                     | true
    "test-itr-skipping"               | [TestSucceedKarate]       | 3                   | [new TestIdentifier("[org/example/test_succeed] test succeed", "first scenario", null, null)]         | isSkippingSupported(FileUtils.KARATE_VERSION)
    "test-itr-skipping-parameterized" | [TestParameterizedKarate] | 3                   | [
      new TestIdentifier("[org/example/test_parameterized] test parameterized", "first scenario as an outline", '{"param":"\\\'a\\\'","value":"aa"}', null)
    ]                                                                                                                                                                                           | isSkippingSupported(FileUtils.KARATE_VERSION)
    "test-itr-unskippable"            | [TestUnskippableKarate]   | 3                   | [new TestIdentifier("[org/example/test_unskippable] test unskippable", "first scenario", null, null)] | isSkippingSupported(FileUtils.KARATE_VERSION)
  }

  private void runTests(List<Class<?>> tests) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[tests.size()]
    for (i in 0..<tests.size()) {
      selectors[i] = selectClass(tests[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new JupiterTestEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)

    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "karate"
  }

  @Override
  String instrumentedLibraryVersion() {
    return FileUtils.KARATE_VERSION
  }

  boolean isSkippingSupported(String frameworkVersion) {
    // earlier Karate version contain a bug that does not allow skipping scenarios
    frameworkVersion >= "1.2.0"
  }

  boolean isSetupTagSupported(String frameworkVersion) {
    frameworkVersion >= "1.3.0"
  }
}
