package datadog.trace.instrumentation.jedis30;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "trace.span.attribute.schema", value = "v0")
@WithConfig(key = "trace.db.client.split-by-instance", value = "true")
class Jedis30ClientV0ForkedTest extends Jedis30ClientTest {

  @Override
  protected String service() {
    return "redis";
  }

  @Override
  protected String operation() {
    return "redis.query";
  }
}
