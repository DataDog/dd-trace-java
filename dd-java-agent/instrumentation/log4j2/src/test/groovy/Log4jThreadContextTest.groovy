import datadog.trace.agent.test.log.injection.LogContextInjectionTestBase
import org.apache.logging.log4j.ThreadContext

class Log4jThreadContextTest extends LogContextInjectionTestBase {

  @Override
  void put(String key, Object value) {
    ThreadContext.put(key, value as String)
  }

  @Override
  Object get(String key) {
    return ThreadContext.get(key)
  }

  @Override
  void remove(String key) {
    ThreadContext.remove(key)
  }

  @Override
  void clear() {
    ThreadContext.clearAll()
  }

  @Override
  Map<String, Object> getMap() {
    return ThreadContext.getImmutableContext()
  }
}
