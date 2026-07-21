import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.karate2.KarateUtils
import datadog.trace.instrumentation.karate2.TestEventsHandlerHolder
import org.example.TestSucceedKarate

@DisableTestTrace(reason = "avoid self-tracing")
class KarateV2RetryDisabledForkedTest extends CiVisibilityInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.test-retry.enabled", "false")
  }

  def "finishes scenarios when retry instrumentation is disabled"() {
    setup:
    TestEventsHandlerHolder.start()

    when:
    new TestSucceedKarate().test()
    assertSpansData("test-retry-disabled")

    then:
    noExceptionThrown()

    cleanup:
    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "karate"
  }

  @Override
  String instrumentedLibraryVersion() {
    return KarateUtils.getKarateVersion()
  }
}
