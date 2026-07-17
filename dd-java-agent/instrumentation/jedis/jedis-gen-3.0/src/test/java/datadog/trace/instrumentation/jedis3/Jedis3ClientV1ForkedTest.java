package datadog.trace.instrumentation.jedis3;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_SERVICE;

import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;

/**
 * Tests Jedis 3.x instrumentation with the v1 naming schema. In v1, Redis spans use the
 * application's own service name and the operation name "redis.command". This test runs in a forked
 * JVM to isolate the naming schema configuration.
 *
 * <p>In v1 naming, peer service is automatically calculated from peer.hostname, enabling service
 * topology visualization in Datadog APM.
 *
 * <p>All test methods are inherited from {@link Jedis3ClientTest}, which covers SET, GET, DEL,
 * HSET, HMSET, HGETALL, ZADD, ZRANGEBYSCORE, RANDOMKEY commands and connection error scenarios with
 * full span structure assertions (service, operation, resource, type, and all tags including peer
 * service).
 */
class Jedis3ClientV1ForkedTest extends Jedis3ClientTest {

  static {
    System.setProperty("dd.trace.span.attribute.schema", "v1");
  }

  @Override
  protected String service() {
    return Config.get().getServiceName();
  }

  @Override
  protected String operation() {
    return "redis.command";
  }

  @Override
  protected TagsMatcher[] peerServiceTags() {
    return new TagsMatcher[] {
      tag(PEER_SERVICE, is("localhost")), tag(DDTags.PEER_SERVICE_SOURCE, is(PEER_HOSTNAME)),
    };
  }
}
