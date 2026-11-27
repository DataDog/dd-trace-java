package datadog.trace.instrumentation.redisson30;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redis.testcontainers.RedisContainer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class PingSuppressionTest extends AbstractInstrumentationTest {
  private static final Pattern PING_CALLS = Pattern.compile("cmdstat_ping:calls=(\\d+)");

  private static RedisContainer redis;

  @BeforeAll
  static void startRedis() {
    redis =
        new RedisContainer(DockerImageName.parse(System.getProperty("test.redis.image")))
            .waitingFor(Wait.forListeningPort());
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  @Test
  void keepalivePingCreatesNoSpans() throws Exception {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress(redis.getRedisURI())
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(1)
        .setSubscriptionConnectionMinimumIdleSize(1)
        .setSubscriptionConnectionPoolSize(1)
        .setPingConnectionInterval(50);
    config.setUseScriptCache(false);

    RedissonClient client = Redisson.create(config);
    try {
      // setting up a connection sends one traced PING, and newer versions finish setup after
      // create() returns, so let it settle and only count PING spans from then on
      Thread.sleep(200);
      int setupPingSpans = pingSpanCount();
      long pingsBefore = serverPingCount();

      runUnderTrace(
          "parent",
          () -> {
            client.getBucket("foo").set("bar");
            return null;
          });
      // several ping intervals
      Thread.sleep(500);
      assertTrue(serverPingCount() > pingsBefore, "Redisson didn't ping");
      assertEquals(setupPingSpans, pingSpanCount(), "keepalive PING traced");
    } finally {
      client.shutdown();
    }

    blockUntilTracesMatch(traces -> find("SET") != null);
    assertEquals(find("parent").getSpanId(), find("SET").getParentId());
  }

  private int pingSpanCount() {
    int count = 0;
    for (DDSpan span : allSpans()) {
      if ("PING".contentEquals(span.getResourceName())) {
        count++;
      }
    }
    return count;
  }

  /** Reads the server's {@code PING} count without a traced client. */
  private static long serverPingCount() throws Exception {
    String info = redis.execInContainer("redis-cli", "info", "commandstats").getStdout();
    Matcher matcher = PING_CALLS.matcher(info);
    return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
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
