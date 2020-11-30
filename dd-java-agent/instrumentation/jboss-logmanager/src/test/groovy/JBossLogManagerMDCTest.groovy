import datadog.trace.agent.test.log.injection.LogContextInjectionTestBase
import org.jboss.logmanager.MDC

class JBossLogManagerMDCTest extends LogContextInjectionTestBase {

  @Override
  void put(String key, Object value) {
    MDC.putObject(key, value)
  }

  @Override
  Object get(String key) {
    return MDC.getObject(key)
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
    return MDC.copyObject()
  }
}
