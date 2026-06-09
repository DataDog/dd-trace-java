package datadog.trace.instrumentation.jedis30;

import datadog.trace.api.Config;
import datadog.trace.junit.utils.config.WithConfig;

/**
 * Jedis 3.x instrumentation test for V1 (current) naming conventions.
 *
 * <p>V1 naming uses:
 *
 * <ul>
 *   <li>Service name: the application's configured service name (from {@link Config})
 *   <li>Operation name: {@code "redis.command"}
 * </ul>
 *
 * <p>Uses the {@code ForkedTest} suffix to run in a separate JVM, ensuring the V1 span attribute
 * schema configuration does not conflict with V0 tests.
 */
@WithConfig(key = "trace.span.attribute.schema", value = "v1")
class Jedis30ClientV1ForkedTest extends Jedis30ClientTest {

  @Override
  protected String service() {
    return Config.get().getServiceName();
  }

  @Override
  protected String operation() {
    return "redis.command";
  }
}
