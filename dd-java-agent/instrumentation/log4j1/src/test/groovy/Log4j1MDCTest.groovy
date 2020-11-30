import datadog.trace.agent.test.log.injection.LogContextInjectionTestBase
import datadog.trace.api.Platform
import org.apache.log4j.MDC
import spock.lang.Requires

/**
 It looks like log4j1 is broken for any java version that doesn't have '.' in version number
 - it thinks it runs on ancient version. For example this happens for java13.
 See {@link org.apache.log4j.helpers.Loader}.
 */
@Requires({ !Platform.isJavaVersionAtLeast(9) })
class Log4j1MDCTest extends LogContextInjectionTestBase {

  @Override
  void put(String key, Object value) {
    MDC.put(key, value)
  }

  @Override
  Object get(String key) {
    return MDC.get(key)
  }

  @Override
  void remove(String key) {
    MDC.remove(key)
  }

  @Override
  void clear() {
    MDC.clear()
  }

  @Override
  Map<String, Object> getMap() {
    return MDC.getContext()
  }
}
