package datadog.trace.instrumentation.jedis3;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.DDSpanTypes;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.embedded.RedisServer;

/**
 * Abstract base test for Jedis 3.x instrumentation. Verifies that Redis commands produce correctly
 * tagged spans with the expected service name, operation name, resource name, span type, and all
 * relevant cache/Redis tags including peer service tags for service topology visualization.
 *
 * <p>Subclasses must implement {@link #service()} and {@link #operation()} to provide the expected
 * service name and operation name for the naming schema version under test. V1 schema subclasses
 * should also override {@link #peerServiceTags()} to assert peer.service and
 * _dd.peer.service.source tags.
 *
 * <p><b>Note on cache semantic tags:</b>
 *
 * <ul>
 *   <li>{@code db.redis.dbIndex} — Captured via {@code BinaryClient.getDB()} by checking if the
 *       {@code Connection} instance is a {@code BinaryClient} subclass at runtime. The {@code
 *       Connection} base class does not expose the database index directly, but the actual runtime
 *       type ({@code Client}/{@code BinaryClient}) does.
 *   <li>{@code redis.raw_command} — Not captured by any Redis integration in the tracer (Jedis,
 *       Lettuce, Redisson). The command name is captured as the span's resource name (e.g. "SET",
 *       "GET"), but the full command with arguments is intentionally omitted to avoid capturing
 *       sensitive key/value data in traces.
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class Jedis3ClientTest extends AbstractInstrumentationTest {

  private int port;
  private RedisServer redisServer;
  private Jedis jedis;

  /**
   * Returns the expected service name for spans in the current naming schema version.
   *
   * @return the expected service name
   */
  protected abstract String service();

  /**
   * Returns the expected operation name for spans in the current naming schema version.
   *
   * @return the expected operation name
   */
  protected abstract String operation();

  /**
   * Returns additional tag matchers for peer service tags. In v0 naming, peer service is not
   * calculated so this returns an empty array. V1 tests override this to assert peer.service and
   * _dd.peer.service.source tags.
   *
   * @return additional tag matchers for peer service assertions
   */
  protected TagsMatcher[] peerServiceTags() {
    return new TagsMatcher[0];
  }

  /**
   * Returns the standard set of tag matchers for a Redis command span, including connection tags
   * (peer hostname, port) and any peer service tags relevant to the naming schema version.
   *
   * @param expectedPort the expected Redis server port
   * @return tag matchers array for use in span assertions
   */
  private TagsMatcher[] redisTags(int expectedPort) {
    TagsMatcher[] peerService = peerServiceTags();
    TagsMatcher[] base =
        new TagsMatcher[] {
          defaultTags(),
          tag(COMPONENT, is("redis-command")),
          tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
          tag(DB_TYPE, is("redis")),
          tag("db.redis.dbIndex", is(0)),
          tag(PEER_HOSTNAME, is("localhost")),
          tag(PEER_PORT, is(expectedPort)),
        };
    if (peerService.length == 0) {
      return base;
    }
    TagsMatcher[] result = new TagsMatcher[base.length + peerService.length];
    System.arraycopy(base, 0, result, 0, base.length);
    System.arraycopy(peerService, 0, result, base.length, peerService.length);
    return result;
  }

  @BeforeAll
  void setUp() throws IOException {
    port = PortUtils.randomOpenPort();
    redisServer =
        RedisServer.newRedisServer()
            .setting("bind 127.0.0.1")
            .setting("maxmemory 128M")
            .port(port)
            .build();
    redisServer.start();
    jedis = new Jedis("localhost", port);
  }

  @AfterAll
  void stopRedis() throws IOException {
    if (jedis != null) {
      jedis.close();
    }
    if (redisServer != null) {
      redisServer.stop();
    }
  }

  @BeforeEach
  void flushRedis() throws InterruptedException, TimeoutException {
    jedis.flushAll();
    writer.waitForTraces(1);
    writer.start();
  }

  @Test
  void setCommand() {
    jedis.set("foo", "bar");

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void getCommand() {
    jedis.set("foo", "bar");
    String value = jedis.get("foo");

    assertEquals("bar", value);

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("GET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void commandWithNoArguments() {
    jedis.set("foo", "bar");
    String value = jedis.randomKey();

    assertEquals("foo", value);

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("RANDOMKEY")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void hmsetAndHgetAllCommands() {
    Map<String, String> hash = new HashMap<>();
    hash.put("key1", "value1");
    hash.put("key2", "value2");
    jedis.hmset("map", hash);

    Map<String, String> result = jedis.hgetAll("map");

    assertNotNull(result);
    assertEquals(hash, result);

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("HMSET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("HGETALL")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void zaddAndZrangeByScoreCommands() {
    jedis.zadd("foo", 1d, "a");
    jedis.zadd("foo", 10d, "b");
    jedis.zadd("foo", 0.1d, "c");
    jedis.zadd("foo", 2d, "d");

    Set<String> expected = new HashSet<>();
    expected.add("a");
    expected.add("c");
    expected.add("d");
    Set<String> result = jedis.zrangeByScore("foo", 0d, 2d);

    assertNotNull(result);
    assertEquals(expected, result);

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZRANGEBYSCORE")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void delCommand() {
    jedis.set("foo", "bar");
    long deleted = jedis.del("foo");

    assertTrue(deleted > 0);

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))),
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("DEL")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void hsetCommand() {
    jedis.hset("myhash", "field1", "value1");

    assertTraces(
        trace(
            span()
                .serviceName(service())
                .operationName(operation())
                .resourceName("HSET")
                .type(DDSpanTypes.REDIS)
                .tags(redisTags(port))));
  }

  @Test
  void connectionErrorProducesSpanWithErrorTags() {
    // Use a port where no Redis server is listening to trigger a connection error
    int badPort = PortUtils.randomOpenPort();
    Jedis badJedis = new Jedis("localhost", badPort);
    try {
      assertThrows(JedisConnectionException.class, () -> badJedis.get("foo"));

      TagsMatcher[] baseTags = redisTags(badPort);
      TagsMatcher[] errorTags = new TagsMatcher[baseTags.length + 1];
      System.arraycopy(baseTags, 0, errorTags, 0, baseTags.length);
      errorTags[baseTags.length] = error(JedisConnectionException.class);

      assertTraces(
          trace(
              span()
                  .serviceName(service())
                  .operationName(operation())
                  .resourceName("GET")
                  .type(DDSpanTypes.REDIS)
                  .error()
                  .tags(errorTags)));
    } finally {
      badJedis.close();
    }
  }
}
