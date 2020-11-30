import datadog.trace.agent.test.log.injection.LogContextInjectionTestBase
import org.slf4j.MDC

class Slf4jMDCTest extends LogContextInjectionTestBase {

  @Override
  void put(String key, Object value) {
    MDC.put(key, value as String)
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
    return MDC.getCopyOfContextMap()
  }
}
