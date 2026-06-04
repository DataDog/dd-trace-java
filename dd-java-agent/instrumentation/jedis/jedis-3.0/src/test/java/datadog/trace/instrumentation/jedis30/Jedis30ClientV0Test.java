package datadog.trace.instrumentation.jedis30;

/**
 * Jedis 3.x instrumentation test for V0 (legacy) naming conventions.
 *
 * <p>V0 naming uses:
 *
 * <ul>
 *   <li>Service name: {@code "redis"} (library-specific)
 *   <li>Operation name: {@code "redis.query"}
 * </ul>
 */
class Jedis30ClientV0Test extends Jedis30ClientTest {

  @Override
  protected String service() {
    return "redis";
  }

  @Override
  protected String operation() {
    return "redis.query";
  }
}
