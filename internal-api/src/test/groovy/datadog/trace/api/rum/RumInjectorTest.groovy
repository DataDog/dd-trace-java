package datadog.trace.api.rum

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class RumInjectorTest extends DDSpecification {
  static final String UTF8 = "UTF-8"

  void 'disabled injector'(){
    setup:
    Config config = mock(Config)
    RumInjector injector

    when:
    when(config.isRumEnabled()).thenReturn(false)
    injector = new RumInjector(config)

    then:
    !injector.isEnabled()
    injector.getMarkerBytes(UTF8) == null
    injector.getSnippetBytes(UTF8) == null
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
    injector.getMarkerBytes(UTF8) == null
    injector.getSnippetBytes(UTF8) == null
    injector.getSnippetChars() == null
    injector.getMarkerChars() == null
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
    injector.getMarkerBytes(UTF8) != null
    injector.getSnippetBytes(UTF8) != null
    injector.getSnippetChars() != null
    injector.getMarkerChars() != null
  }
}
