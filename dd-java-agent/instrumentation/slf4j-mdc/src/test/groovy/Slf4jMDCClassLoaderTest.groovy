import datadog.trace.agent.test.log.injection.LogContextInjectionTestBase
import org.slf4j.MDC
import spock.lang.Shared

class Slf4jMDCClassLoaderTest extends LogContextInjectionTestBase {
  // Just make sure that we load the MDC in this class loader
  @Shared
  String foo = MDC.get("foo")

  @Shared
  ClassLoaderTestHelper helper = new ClassLoaderTestHelper()

  @Override
  void put(String key, Object value) {
    helper.put(key, value as String)
  }

  @Override
  Object get(String key) {
    return helper.get(key)
  }

  @Override
  void remove(String key) {
    helper.remove(key)
  }

  @Override
  void clear() {
    helper.clear()
  }

  @Override
  Map<String, Object> getMap() {
    return helper.getMap()
  }
}
