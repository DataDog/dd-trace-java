package datadog.trace.api.rum

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import java.nio.charset.StandardCharsets

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class RumInjectorTest extends DDSpecification {
  public static final String UTF8 = StandardCharsets.UTF_8.name()

  void 'disabled injector'(){
    setup:
    Config config = mock(Config)
    RumInjector injector

    when:
    when(config.isRumEnabled()).thenReturn(false)
    injector = new RumInjector(config)

    then:
    !injector.isEnabled()
    injector.getMarker(UTF8) == null
    injector.getSnippet(UTF8) == null
  }

  void 'invalid config injector'() {
    setup:
    Config config = mock(Config)
    RumInjector injector

    when:
    when(config.isRumEnabled()).thenReturn(true)
    when(config.rumInjectorConfig).thenReturn(null)
    injector = new RumInjector(config)

    then:
    !injector.isEnabled()
    injector.getMarker(UTF8) == null
    injector.getSnippet(UTF8) == null
  }

  void 'enabled injector'() {
    setup:
    Config config = mock(Config)
    def injectorConfig = mock(RumInjectorConfig)
    RumInjector injector

    when:
    when(config.isRumEnabled()).thenReturn(true)
    when(config.rumInjectorConfig).thenReturn(injectorConfig)
    when(injectorConfig.snippet).thenReturn("<script></script>")
    injector = new RumInjector(config)

    then:
    injector.isEnabled()
    injector.getMarker(UTF8) != null
    injector.getSnippet(UTF8) != null
  }
}
