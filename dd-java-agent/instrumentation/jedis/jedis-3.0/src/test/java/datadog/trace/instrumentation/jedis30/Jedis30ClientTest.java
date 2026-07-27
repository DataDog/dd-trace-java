package datadog.trace.instrumentation.jedis30;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.agent.test.utils.TraceUtils;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.embedded.RedisServer;

abstract class Jedis30ClientTest extends AbstractInstrumentationTest {

  private static int port;
  private static RedisServer redisServer;
  private static Jedis jedis;

  protected abstract String service();

  protected abstract String operation();

  @BeforeAll
  static void setupSpec() throws IOException {
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
  static void cleanupSpec() throws IOException {
    redisServer.stop();
    jedis.close();
  }

  @BeforeEach
  void setup() throws InterruptedException, TimeoutException {
    TraceUtils.runUnderTrace(
        "cleanup",
        () -> {
          jedis.flushAll();
          return activeSpan();
        });
    writer.waitForTraces(1);
    writer.start();
  }

  private SpanMatcher redisSpan(String resourceName) {
    return span()
        .serviceName(service())
        .operationName(Pattern.compile(Pattern.quote(operation())))
        .resourceName(Pattern.compile(Pattern.quote(resourceName)))
        .type(DDSpanTypes.REDIS)
        .measured()
        .tags(
            tag(Tags.COMPONENT, equalsString("redis-command")),
            tag(Tags.SPAN_KIND, equalsString(Tags.SPAN_KIND_CLIENT)),
            tag(Tags.DB_TYPE, equalsString("redis")),
            tag(Tags.PEER_HOSTNAME, equalsString("localhost")),
            tag("_dd.peer.service.source", any()),
            tag("_dd.svc_src", any()),
            tag("peer.service", any()),
            defaultTags());
  }

  /** Matches tag values using toString() to handle UTF8BytesString vs String comparison. */
  private static <T> datadog.trace.test.junit.utils.assertions.Matcher<T> equalsString(
      String expected) {
    return validates(v -> v != null && v.toString().equals(expected));
  }

  @Test
  void setCommand() {
    jedis.set("foo", "bar");

    assertTraces(trace(redisSpan("SET")));
  }

  @Test
  void getCommand() {
    jedis.set("foo", "bar");
    String value = jedis.get("foo");

    assertEquals("bar", value);

    assertTraces(trace(redisSpan("SET")), trace(redisSpan("GET")));
  }

  @Test
  void commandWithNoArguments() {
    jedis.set("foo", "bar");
    String value = jedis.randomKey();

    assertEquals("foo", value);

    assertTraces(trace(redisSpan("SET")), trace(redisSpan("RANDOMKEY")));
  }

  @Test
  void hmsetAndHgetAllCommands() {
    Map<String, String> h = new HashMap<>();
    h.put("key1", "value1");
    h.put("key2", "value2");
    jedis.hmset("map", h);

    Map<String, String> result = jedis.hgetAll("map");

    assertNotNull(result);
    assertEquals(h, result);

    assertTraces(trace(redisSpan("HMSET")), trace(redisSpan("HGETALL")));
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
    Set<String> val = jedis.zrangeByScore("foo", 0d, 2d);

    assertNotNull(val);
    assertEquals(expected, val);

    assertTraces(
        trace(redisSpan("ZADD")),
        trace(redisSpan("ZADD")),
        trace(redisSpan("ZADD")),
        trace(redisSpan("ZADD")),
        trace(redisSpan("ZRANGEBYSCORE")));
  }

  @Test
  void pipelineOperations() {
    Pipeline pipeline = jedis.pipelined();
    pipeline.set("pipe-key1", "value1");
    pipeline.set("pipe-key2", "value2");
    Response<String> getResponse1 = pipeline.get("pipe-key1");
    Response<String> getResponse2 = pipeline.get("pipe-key2");
    pipeline.sync();

    assertEquals("value1", getResponse1.get());
    assertEquals("value2", getResponse2.get());

    assertTraces(
        trace(redisSpan("SET")),
        trace(redisSpan("SET")),
        trace(redisSpan("GET")),
        trace(redisSpan("GET")));
  }

  @Test
  void transactionOperations() {
    Transaction transaction = jedis.multi();
    transaction.set("tx-key1", "tx-value1");
    transaction.set("tx-key2", "tx-value2");
    Response<String> getResponse = transaction.get("tx-key1");
    transaction.exec();

    assertEquals("tx-value1", getResponse.get());

    // MULTI, SET, SET, GET, EXEC each produce a span
    assertTraces(
        trace(redisSpan("MULTI")),
        trace(redisSpan("SET")),
        trace(redisSpan("SET")),
        trace(redisSpan("GET")),
        trace(redisSpan("EXEC")));
  }

  @Test
  void additionalCommandsDelIncrExpireTtlExists() {
    jedis.set("count-key", "0");
    jedis.incr("count-key");
    jedis.incr("count-key");
    String countVal = jedis.get("count-key");
    jedis.expire("count-key", 3600);
    Long ttlVal = jedis.ttl("count-key");
    Boolean existsVal = jedis.exists("count-key");
    jedis.del("count-key");
    Boolean existsAfterDel = jedis.exists("count-key");

    assertEquals("2", countVal);
    assertTrue(ttlVal > 0);
    assertTrue(existsVal);
    assertFalse(existsAfterDel);

    assertTraces(
        trace(redisSpan("SET")),
        trace(redisSpan("INCR")),
        trace(redisSpan("INCR")),
        trace(redisSpan("GET")),
        trace(redisSpan("EXPIRE")),
        trace(redisSpan("TTL")),
        trace(redisSpan("EXISTS")),
        trace(redisSpan("DEL")),
        trace(redisSpan("EXISTS")));
  }

  @Test
  void wrongTypeCommandSetsErrorTags() {
    // SET stores a string value, then LPUSH expects a list — Redis returns a WRONGTYPE error
    jedis.set("string-key", "value");
    assertThrows(JedisDataException.class, () -> jedis.lpush("string-key", "element"));

    assertTraces(
        trace(redisSpan("SET").error(false)),
        trace(
            redisSpan("LPUSH")
                .error()
                .tags(
                    tag(Tags.COMPONENT, equalsString("redis-command")),
                    tag(Tags.SPAN_KIND, equalsString(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, equalsString("redis")),
                    tag(Tags.PEER_HOSTNAME, equalsString("localhost")),
                    tag("_dd.peer.service.source", any()),
                    tag("_dd.svc_src", any()),
                    tag("peer.service", any()),
                    error(JedisDataException.class),
                    defaultTags())));
  }
}
