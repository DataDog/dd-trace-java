package datadog.trace.instrumentation.jedis30;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.includes;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
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
import datadog.trace.api.DDTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.embedded.RedisServer;

/**
 * Base test class for Jedis 3.x client instrumentation.
 *
 * <p>Verifies that Redis commands produce correctly tagged spans through the jedis-3.0
 * instrumentation module. Each test exercises a different Redis command and asserts the expected
 * span structure including service name, operation name, resource name, span type, and all semantic
 * tags (component, span.kind, db.type, peer.hostname, peer.port).
 *
 * <p>Concrete subclasses provide the expected service name and operation name for versioned naming
 * (V0 vs V1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class Jedis30ClientTest extends AbstractInstrumentationTest {

  private final int port = PortUtils.randomOpenPort();

  private RedisServer redisServer;
  private Jedis jedis;

  /** Returns the expected service name for spans (varies by naming version). */
  protected abstract String service();

  /** Returns the expected operation name for spans (varies by naming version). */
  protected abstract String operation();

  /**
   * Returns a {@link TagsMatcher} that asserts the {@code _dd.peer.service.source} tag is set to
   * the given source tag when peer service calculation is supported (V1 naming schema), or is a
   * no-op when peer service is not supported (V0 naming schema).
   *
   * <p>This mirrors the Groovy {@code peerServiceFrom()} helper in {@code TagsAssert}.
   */
  protected TagsMatcher peerServiceFrom(String sourceTag) {
    if (SpanNaming.instance().namingSchema().peerService().supports()) {
      return tag(DDTags.PEER_SERVICE_SOURCE, is(sourceTag));
    }
    // V0 schema: peer service tags are not set; defaultTags() already covers them with any()
    return includes();
  }

  @BeforeAll
  void setupRedis() throws IOException {
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
  void tearDownRedis() throws IOException {
    if (jedis != null) {
      jedis.close();
    }
    if (redisServer != null) {
      redisServer.stop();
    }
  }

  /**
   * Flushes Redis data and resets the test writer between tests so each test starts with a clean
   * state. Runs after the superclass {@code @BeforeEach} which flushes the tracer and clears the
   * writer, so we just need to clear Redis data and re-clear the writer to consume the FLUSHALL
   * span.
   */
  @BeforeEach
  void resetRedis() throws Exception {
    if (jedis != null) {
      jedis.flushAll();
      writer.waitForTraces(1);
      writer.start();
    }
  }

  @Test
  void setCommand() throws Exception {

    jedis.set("foo", "bar");

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void getCommand() throws Exception {

    jedis.set("foo", "bar");
    String value = jedis.get("foo");

    assertEquals("bar", value);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("GET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("GET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void commandWithNoArguments() throws Exception {

    jedis.set("foo", "bar");
    String value = jedis.randomKey();

    assertEquals("foo", value);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("RANDOMKEY")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("RANDOMKEY")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void hmsetAndHgetAllCommands() throws Exception {

    Map<String, String> h = new HashMap<>();
    h.put("key1", "value1");
    h.put("key2", "value2");
    jedis.hmset("map", h);

    Map<String, String> result = jedis.hgetAll("map");

    assertNotNull(result);
    assertEquals(h, result);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("HMSET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("HMSET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("HGETALL")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("HGETALL")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void zaddAndZrangeByScoreCommands() throws Exception {

    jedis.zadd("foo", 1d, "a");
    jedis.zadd("foo", 10d, "b");
    jedis.zadd("foo", 0.1d, "c");
    jedis.zadd("foo", 2d, "d");

    Set<String> expected = new HashSet<>();
    expected.add("a");
    expected.add("c");
    expected.add("d");
    Set<String> val = jedis.zrangeByScore("foo", 0d, 2d);

    assertNotNull(val);
    assertEquals(expected, val);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("ZADD")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("ZADD")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("ZADD")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZADD")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("ZADD")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("ZRANGEBYSCORE")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("ZRANGEBYSCORE")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void lpushAndLrangeCommands() throws Exception {

    jedis.lpush("mylist", "a", "b", "c");
    List<String> result = jedis.lrange("mylist", 0, -1);

    assertNotNull(result);
    assertTrue(result.contains("a"));
    assertTrue(result.contains("b"));
    assertTrue(result.contains("c"));

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("LPUSH")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("LPUSH")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("LRANGE")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("LRANGE")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void delCommand() throws Exception {

    jedis.set("mykey", "to-delete");
    Long deleted = jedis.del("mykey");

    assertEquals(1L, deleted);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("DEL")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("DEL")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void saddCommand() throws Exception {

    Long added = jedis.sadd("myset", "member1", "member2", "member3");

    assertTrue(added > 0);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SADD")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SADD")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void smembersCommand() throws Exception {

    jedis.sadd("myset", "x", "y", "z");
    Set<String> result = jedis.smembers("myset");

    assertNotNull(result);
    assertEquals(3, result.size());
    assertTrue(result.contains("x"));
    assertTrue(result.contains("y"));
    assertTrue(result.contains("z"));

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SADD")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SADD")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SMEMBERS")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SMEMBERS")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void incrCommand() throws Exception {

    jedis.set("counter", "10");
    Long result = jedis.incr("counter");

    assertEquals(11L, result);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("INCR")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("INCR")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void existsCommand() throws Exception {

    jedis.set("mykey", "present");
    Boolean result = jedis.exists("mykey");

    assertTrue(result);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("EXISTS")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("EXISTS")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void expireCommand() throws Exception {

    jedis.set("mykey", "expiring");
    Long result = jedis.expire("mykey", 60);

    assertEquals(1L, result);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("EXPIRE")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("EXPIRE")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void msetCommand() throws Exception {

    String result = jedis.mset("key1", "val1", "key2", "val2", "key3", "val3");

    assertEquals("OK", result);

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("MSET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("MSET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  @Test
  void mgetCommand() throws Exception {

    jedis.mset("key1", "val1", "key2", "val2", "key3", "val3");
    List<String> result = jedis.mget("key1", "key2", "key3");

    assertNotNull(result);
    assertEquals(3, result.size());
    assertEquals("val1", result.get(0));
    assertEquals("val2", result.get(1));
    assertEquals("val3", result.get(2));

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("MSET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("MSET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("MGET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("MGET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  /**
   * Verifies that when a GET is issued on a key holding a non-string type (list), the commands are
   * still traced. In Jedis 3.x, the instrumentation wraps Connection.sendCommand(), so the DEL,
   * LPUSH, and GET commands are all traced. The WRONGTYPE error is thrown during response parsing,
   * outside the instrumented method.
   */
  @Test
  void getErrorWrongTypeCommand() throws Exception {

    jedis.del("wrongtype");
    jedis.lpush("wrongtype", "item1", "item2");
    assertThrows(JedisDataException.class, () -> jedis.get("wrongtype"));

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("DEL")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("DEL")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("LPUSH")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("LPUSH")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))),
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("GET")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("GET")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  /**
   * Verifies that when a Redis command triggers a server-side error (e.g. SETEX with a negative
   * TTL), the SETEX command is still traced. Note: in Jedis 3.x, the instrumentation wraps
   * Connection.sendCommand(), which sends the command to Redis successfully. The JedisDataException
   * is thrown later during response parsing (getStatusCodeReply), which is outside the instrumented
   * method, so the span is not marked as errored.
   */
  @Test
  void commandTracedWhenResponseParsingFails() throws Exception {

    // SETEX with a negative TTL causes Redis to return an error during reply parsing
    assertThrows(JedisDataException.class, () -> jedis.setex("errorkey", -1, "value"));

    assertTraces(
        trace(
            span()
                .root()
                .serviceName(service())
                .operationName(operation())
                .resourceName("SETEX")
                .type(DDSpanTypes.REDIS)
                .tags(
                    defaultTags(),
                    tag(COMPONENT, is("redis-command")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("redis")),
                    tag("redis.raw_command", is("SETEX")),
                    tag(PEER_HOSTNAME, is("localhost")),
                    tag(PEER_PORT, is(port)),
                    peerServiceFrom(PEER_HOSTNAME))));
  }

  /**
   * Verifies that spans produced under an active parent trace are correctly linked as child spans.
   */
  @Test
  void spanUnderParentTrace() throws Exception {

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      jedis.set("parentkey", "parentval");
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    // The tracer may write the child and parent as separate trace fragments (V0) or as a
    // single trace containing both spans (V1). Wait for at least 1 trace, then check if all
    // spans have arrived before waiting for a potential second fragment.
    writer.waitForTraces(1);
    int totalSpans = 0;
    for (java.util.List<datadog.trace.core.DDSpan> f : writer) {
      totalSpans += f.size();
    }
    if (totalSpans < 2) {
      writer.waitForTraces(2);
    }

    // Merge trace fragments that share the same trace ID for assertion
    java.util.Map<Long, java.util.List<datadog.trace.core.DDSpan>> byTraceId =
        new java.util.LinkedHashMap<>();
    for (java.util.List<datadog.trace.core.DDSpan> fragment : writer) {
      if (!fragment.isEmpty()) {
        long tid = fragment.get(0).getTraceId().toLong();
        byTraceId.computeIfAbsent(tid, k -> new java.util.ArrayList<>()).addAll(fragment);
      }
    }
    java.util.List<java.util.List<datadog.trace.core.DDSpan>> merged =
        new java.util.ArrayList<>(byTraceId.values());
    // Sort spans within each trace: root first
    for (java.util.List<datadog.trace.core.DDSpan> trace : merged) {
      trace.sort(java.util.Comparator.comparingLong(datadog.trace.core.DDSpan::getParentId));
    }

    // Assert on the merged trace
    org.junit.jupiter.api.Assertions.assertEquals(1, merged.size(), "Expected 1 logical trace");
    java.util.List<datadog.trace.core.DDSpan> trace = merged.get(0);
    org.junit.jupiter.api.Assertions.assertEquals(2, trace.size(), "Expected 2 spans");
    datadog.trace.core.DDSpan root = trace.get(0);
    datadog.trace.core.DDSpan child = trace.get(1);
    org.junit.jupiter.api.Assertions.assertEquals(0, root.getParentId(), "Root has no parent");
    org.junit.jupiter.api.Assertions.assertEquals(
        "parent", root.getOperationName().toString(), "Root operation name");
    org.junit.jupiter.api.Assertions.assertEquals(
        root.getSpanId(), child.getParentId(), "Child is parented to root");
    org.junit.jupiter.api.Assertions.assertEquals(
        operation(), child.getOperationName().toString(), "Child operation name");
    org.junit.jupiter.api.Assertions.assertEquals(
        "SET", child.getResourceName().toString(), "Child resource name");
    org.junit.jupiter.api.Assertions.assertEquals(
        DDSpanTypes.REDIS, child.getSpanType().toString(), "Child span type");
    org.junit.jupiter.api.Assertions.assertEquals(
        service(), child.getServiceName(), "Child service name");
    org.junit.jupiter.api.Assertions.assertEquals(
        "redis-command", String.valueOf(child.getTag("component")), "Component tag");
    org.junit.jupiter.api.Assertions.assertEquals(
        SPAN_KIND_CLIENT, String.valueOf(child.getTag("span.kind")), "Span kind tag");
    org.junit.jupiter.api.Assertions.assertEquals(
        "redis", String.valueOf(child.getTag("db.type")), "DB type tag");
    org.junit.jupiter.api.Assertions.assertEquals(
        "SET", String.valueOf(child.getTag("redis.raw_command")), "Redis raw command tag");
    org.junit.jupiter.api.Assertions.assertEquals(
        "localhost", String.valueOf(child.getTag("peer.hostname")), "Peer hostname tag");
    org.junit.jupiter.api.Assertions.assertEquals(port, child.getTag("peer.port"), "Peer port tag");
    if (SpanNaming.instance().namingSchema().peerService().supports()) {
      org.junit.jupiter.api.Assertions.assertEquals(
          PEER_HOSTNAME, child.getTag(DDTags.PEER_SERVICE_SOURCE), "Peer service source tag");
    }
  }
}
