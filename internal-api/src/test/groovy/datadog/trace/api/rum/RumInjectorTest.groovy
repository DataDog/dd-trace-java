package datadog.trace.api.rum

import datadog.trace.api.Config
import datadog.trace.api.InstrumenterConfig
import datadog.trace.test.util.DDSpecification

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class RumInjectorTest extends DDSpecification {
  static final String UTF8 = "UTF-8"

  void 'disabled injector'(){
    setup:
    Config config = mock(Config)
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig)
    RumInjector injector

    when:
    when(instrumenterConfig.isRumEnabled()).thenReturn(false)
    injector = new RumInjector(config, instrumenterConfig)

    then:
    !injector.isEnabled()
    injector.getMarkerBytes(UTF8) == null
    injector.getSnippetBytes(UTF8) == null
  }

  void 'invalid config injector'() {
    setup:
    Config config = mock(Config)
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig)
    RumInjector injector

    when:
    when(instrumenterConfig.isRumEnabled()).thenReturn(true)
    when(config.rumInjectorConfig).thenReturn(null)
    injector = new RumInjector(config, instrumenterConfig)

    then:
    !injector.isEnabled()
    injector.getMarkerBytes(UTF8) == null
    injector.getSnippetBytes(UTF8) == null
    injector.getSnippetChars() == null
    injector.getMarkerChars() == null
  }

  void 'enabled injector'() {
    setup:
    Config config = mock(Config)
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig)
    def injectorConfig = mock(RumInjectorConfig)
    RumInjector injector

    when:
    when(instrumenterConfig.isRumEnabled()).thenReturn(true)
    when(config.rumInjectorConfig).thenReturn(injectorConfig)
    when(injectorConfig.snippet).thenReturn("<script></script>")
    injector = new RumInjector(config, instrumenterConfig)

    then:
    injector.isEnabled()
    injector.getMarkerBytes(UTF8) != null
    injector.getSnippetBytes(UTF8) != null
    injector.getSnippetChars() != null
    injector.getMarkerChars() != null
  }

  void 'set telemetry collector'() {
    setup:
    def telemetryCollector = mock(RumTelemetryCollector)

    when:
    RumInjector.setTelemetryCollector(telemetryCollector)

    then:
    RumInjector.getTelemetryCollector() == telemetryCollector

    cleanup:
    RumInjector.setTelemetryCollector(RumTelemetryCollector.NO_OP)
  }

  void 'return NO_OP when telemetry collector is not set'() {
    when:
    RumInjector.setTelemetryCollector(null)

    then:
    RumInjector.getTelemetryCollector() == RumTelemetryCollector.NO_OP
  }

  void 'enable telemetry with StatsDClient'() {
    when:
    RumInjector.enableTelemetry(mock(datadog.trace.api.StatsDClient))

    then:
    RumInjector.getTelemetryCollector() instanceof datadog.trace.api.rum.RumInjectorMetrics

    cleanup:
    RumInjector.shutdownTelemetry()
  }

  void 'enabling telemetry with a null StatsDClient sets the telemetry collector to NO_OP'() {
    when:
    RumInjector.enableTelemetry(null)

    then:
    RumInjector.getTelemetryCollector() == RumTelemetryCollector.NO_OP
  }

  void 'shutdown telemetry'() {
    setup:
    RumInjector.enableTelemetry(mock(datadog.trace.api.StatsDClient))

    when:
    RumInjector.shutdownTelemetry()

    then:
    RumInjector.getTelemetryCollector() == RumTelemetryCollector.NO_OP
  }

  void 'initialize rum injector'() {
    when:
    RumInjector.enableTelemetry(mock(datadog.trace.api.StatsDClient))
    def telemetryCollector = RumInjector.getTelemetryCollector()
    telemetryCollector.onInitializationSucceed()
    def summary = telemetryCollector.summary()

    then:
    summary.contains("initializationSucceed=1")

    cleanup:
    RumInjector.shutdownTelemetry()
  }

  void 'telemetry integration works end-to-end'() {
    when:
    RumInjector.enableTelemetry(mock(datadog.trace.api.StatsDClient))

    def telemetryCollector = RumInjector.getTelemetryCollector()
    telemetryCollector.onInjectionSucceed("3")
    telemetryCollector.onInjectionFailed("3", "gzip")
    telemetryCollector.onInjectionSkipped("3")
    telemetryCollector.onContentSecurityPolicyDetected("3")
    telemetryCollector.onInjectionResponseSize("3", 256)
    telemetryCollector.onInjectionTime("3", 5L)

    def summary = telemetryCollector.summary()

    then:
    summary.contains("injectionSucceed=1")
    summary.contains("injectionFailed=1")
    summary.contains("injectionSkipped=1")
    summary.contains("contentSecurityPolicyDetected=1")

    cleanup:
    RumInjector.shutdownTelemetry()
  }

  void 'response size telemetry does not throw an exception'() {
    setup:
    def mockStatsDClient = mock(datadog.trace.api.StatsDClient)

    when:
    RumInjector.enableTelemetry(mockStatsDClient)

    def telemetryCollector = RumInjector.getTelemetryCollector()
    telemetryCollector.onInjectionResponseSize("3", 256)
    telemetryCollector.onInjectionResponseSize("3", 512)
    telemetryCollector.onInjectionResponseSize("5", 2048)

    then:
    noExceptionThrown()

    cleanup:
    RumInjector.shutdownTelemetry()
  }

  void 'injection time telemetry does not throw an exception'() {
    setup:
    def mockStatsDClient = mock(datadog.trace.api.StatsDClient)

    when:
    RumInjector.enableTelemetry(mockStatsDClient)

    def telemetryCollector = RumInjector.getTelemetryCollector()
    telemetryCollector.onInjectionTime("5", 5L)
    telemetryCollector.onInjectionTime("5", 10L)
    telemetryCollector.onInjectionTime("3", 20L)

    then:
    noExceptionThrown()

    cleanup:
    RumInjector.shutdownTelemetry()
  }

  void 'concurrent telemetry calls return an accurate summary'() {
    setup:
    RumInjector.enableTelemetry(mock(datadog.trace.api.StatsDClient))
    def telemetryCollector = RumInjector.getTelemetryCollector()
    def threads = []

    when:
    // simulate multiple threads calling telemetry methods
    (1..50).each { i ->
      threads << Thread.start {
        telemetryCollector.onInjectionSucceed("3")
        telemetryCollector.onInjectionFailed("3", "gzip")
        telemetryCollector.onInjectionSkipped("3")
        telemetryCollector.onContentSecurityPolicyDetected("3")
        telemetryCollector.onInjectionResponseSize("3", 256)
        telemetryCollector.onInjectionTime("3", 5L)
      }
    }
    threads*.join()

    def summary = telemetryCollector.summary()

    then:
    summary.contains("injectionSucceed=50")
    summary.contains("injectionFailed=50")
    summary.contains("injectionSkipped=50")
    summary.contains("contentSecurityPolicyDetected=50")

    cleanup:
    RumInjector.shutdownTelemetry()
  }
}
