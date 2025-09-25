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

  // enableTelemetry() checks that INSTANCE.isEnabled() before starting telemetry collection.
  // However, INSTANCE is a static final field created at class loading, so we test whatever the actual RUM configuration is.
  void 'enable telemetry'() {
    when:
    RumInjector.enableTelemetry()
    def collector = RumInjector.getTelemetryCollector()
    def isRumEnabled = RumInjector.get().isEnabled()

    then:
    collector != null
    if (isRumEnabled) {
      collector instanceof RumInjectorMetrics
      collector != RumTelemetryCollector.NO_OP
    } else {
      collector == RumTelemetryCollector.NO_OP
    }
  }

  void 'shutdown telemetry'() {
    setup:
    RumInjector.enableTelemetry()

    when:
    RumInjector.shutdownTelemetry()

    then:
    RumInjector.getTelemetryCollector() == RumTelemetryCollector.NO_OP
  }
}
