import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.*
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit58Test extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                  | tests                            | expectedTracesCount
    "test-before-each-after-each" | [TestSucceedBeforeEachAfterEach] | 2
    "test-before-all-after-all"   | [TestSucceedBeforeAllAfterAll]   | 2
  }

  private static void runTests(List<Class<?>> tests) {
    TestEventsHandlerHolder.startForcefully()

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
    try {
      launcher.execute(launcherReq)
    } catch (Throwable ignored) {
    }

    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "junit5"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JupiterTestEngine.getPackage().getImplementationVersion()
  }
}
