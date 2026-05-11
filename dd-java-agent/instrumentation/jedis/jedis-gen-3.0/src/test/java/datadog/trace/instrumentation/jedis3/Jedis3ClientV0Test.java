package datadog.trace.instrumentation.jedis3;

/**
 * Tests Jedis 3.x instrumentation with the v0 naming schema. In v0, Redis spans use a dedicated
 * service name "redis" and the operation name "redis.query".
 *
 * <p>All test methods are inherited from {@link Jedis3ClientTest}, which covers SET, GET, DEL,
 * HSET, HMSET, HGETALL, ZADD, ZRANGEBYSCORE, RANDOMKEY commands and connection error scenarios with
 * full span structure assertions (service, operation, resource, type, and all tags).
 */
class Jedis3ClientV0Test extends Jedis3ClientTest {

  @Override
  protected String service() {
    return "redis";
  }

  @Override
  protected String operation() {
    return "redis.query";
  }
}
