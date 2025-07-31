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
    def mockTelemetryCollector = mock(RumTelemetryCollector)

    when:
    RumInjector.setTelemetryCollector(mockTelemetryCollector)
    def telemetryCollector = RumInjector.getTelemetryCollector()

    then:
    telemetryCollector == mockTelemetryCollector
  }

  void 'return NO_OP when telemetry collector is not set'() {
    when:
    RumInjector.setTelemetryCollector(null)
    def telemetryCollector = RumInjector.getTelemetryCollector()

    then:
    telemetryCollector == RumTelemetryCollector.NO_OP
  }

  void 'enable telemetry with StatsDClient'() {
    setup:
    def mockStatsDClient = mock(datadog.trace.api.StatsDClient)
    RumInjector.enableTelemetry(mockStatsDClient)

    when:
    def telemetryCollector = RumInjector.getTelemetryCollector()

    then:
    telemetryCollector instanceof datadog.trace.api.rum.RumInjectorMetrics
  }

  void 'enabling telemetry with a null StatsDClient sets the telemetry collector to NO_OP'() {
    when:
    RumInjector.enableTelemetry(null)
    def telemetryCollector = RumInjector.getTelemetryCollector()

    then:
    telemetryCollector == RumTelemetryCollector.NO_OP
  }

  void 'shutdown telemetry'() {
    setup:
    def mockStatsDClient = mock(datadog.trace.api.StatsDClient)
    RumInjector.enableTelemetry(mockStatsDClient)

    when:
    RumInjector.shutdownTelemetry()
    def telemetryCollector = RumInjector.getTelemetryCollector()

    then:
    telemetryCollector == RumTelemetryCollector.NO_OP
  }
}
