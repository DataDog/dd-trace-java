package datadog.trace.instrumentation.jedis30;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.embedded.RedisServer;

/**
 * Assertions look for specific spans rather than whole trace shapes: the server-side {@code PING}
 * count is read with a traced client.
 */
class PoolValidationInstrumentationTest extends AbstractInstrumentationTest {
  private static final Pattern PING_CALLS = Pattern.compile("cmdstat_ping:calls=(\\d+)");

  private static int port;
  private static RedisServer redisServer;

  @BeforeAll
  static void startRedis() throws Exception {
    port = PortUtils.randomOpenPort();
    redisServer =
        RedisServer.newRedisServer()
            // bind to localhost to avoid a firewall popup
            .setting("bind 127.0.0.1")
            .setting("maxmemory 128M")
            .port(port)
            .build();
    redisServer.start();
  }

  @AfterAll
  static void stopRedis() throws Exception {
    redisServer.stop();
  }

  @Test
  void validationPingCreatesNoSpans() throws Exception {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(1);
    config.setTestOnBorrow(true);
    long pingsBefore;
    try (JedisPool pool = new JedisPool(config, "localhost", port)) {
      // warm up so the traced borrow reuses an idle connection, which the pool validates
      pool.getResource().close();

      pingsBefore = serverPingCount();
      runUnderTrace(
          "parent",
          () -> {
            try (Jedis jedis = pool.getResource()) {
              jedis.set("foo", "bar");
            }
            return null;
          });
    }
    assertTrue(serverPingCount() > pingsBefore, "the pool didn't validate on borrow");

    blockUntilTracesMatch(traces -> find("SET") != null);
    assertEquals(find("parent").getSpanId(), find("SET").getParentId());
    for (DDSpan span : allSpans()) {
      assertFalse("PING".contentEquals(span.getResourceName()), "validation traced: " + span);
    }
  }

  /** Reads how many {@code PING}s the server has handled, so validation can't pass vacuously. */
  private static long serverPingCount() {
    try (Jedis jedis = new Jedis("localhost", port)) {
      Matcher matcher = PING_CALLS.matcher(jedis.info("commandstats"));
      return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }
  }

  private DDSpan find(String resource) {
    for (DDSpan span : allSpans()) {
      if (resource.contentEquals(span.getResourceName())) {
        return span;
      }
    }
    return null;
  }

  private List<DDSpan> allSpans() {
    List<DDSpan> spans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      spans.addAll(trace);
    }
    return spans;
  }
}
