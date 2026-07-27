package datadog.trace.instrumentation.jedis30;

import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "trace.span.attribute.schema", value = "v1")
@WithConfig(key = "trace.db.client.split-by-instance", value = "true")
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
