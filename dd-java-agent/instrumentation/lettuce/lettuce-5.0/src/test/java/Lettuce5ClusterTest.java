import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.redis.testcontainers.RedisClusterContainer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.test.util.PollingConditions;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class Lettuce5ClusterTest extends AbstractInstrumentationTest {
  private static final String TEST_SET_KEY = "TESTSETKEY";
  private static final String TEST_SET_VALUE = "TESTSETVAL";

  private RedisClusterContainer redisCluster;
  private RedisClusterClient redisClient;
  private StatefulRedisClusterConnection<String, String> connection;

  @BeforeEach
  void setUpRedis() throws Exception {
    redisCluster =
        new RedisClusterContainer(
            // RedisClusterContainer is built around this preconfigured cluster image.
            DockerImageName.parse("grokzen/redis-cluster:6.2.14"));
    redisCluster.start();

    redisClient = RedisClusterClient.create(RedisURI.create(redisCluster.getRedisURI()));
    redisClient.setOptions(ClusterClientOptions.builder().build());
    connection = redisClient.connect();
    new PollingConditions(30)
        .delay(0.5)
        .eventually(
            () ->
                assertEquals(
                    "OK",
                    connection.sync().set("DD_CLUSTER_READY", "1"),
                    "Redis cluster did not become ready"));

    tracer.flush();
    writer.clear();
  }

  @AfterEach
  void cleanUpRedis() {
    if (connection != null) {
      connection.close();
    }

    if (redisClient != null) {
      redisClient.shutdown(5, 10, TimeUnit.SECONDS);
    }

    if (redisCluster != null) {
      redisCluster.stop();
    }
  }

  @Test
  void clusterCommandSpanHasPeerHostname() throws Exception {
    String result = connection.sync().set(TEST_SET_KEY, TEST_SET_VALUE);

    assertEquals("OK", result);
    assertSetSpanHasPeerHostname();
  }

  @Test
  void asyncClusterCommandSpanHasPeerHostname() throws Exception {
    RedisFuture<String> redisFuture = connection.async().set(TEST_SET_KEY, TEST_SET_VALUE);
    String result = redisFuture.get(3, TimeUnit.SECONDS);

    assertEquals("OK", result);
    assertSetSpanHasPeerHostname();
  }

  private void assertSetSpanHasPeerHostname() throws Exception {
    writer.waitForTraces(1);

    RedisClusterNode expectedNode =
        connection.getPartitions().getPartitionBySlot(SlotHash.getSlot(TEST_SET_KEY));
    assertNotNull(expectedNode, "expected a cluster node for the command key slot");

    List<DDSpan> setSpans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("SET".contentEquals(span.getResourceName())
            && "redis-client".equals(String.valueOf(span.getTag(Tags.COMPONENT)))) {
          setSpans.add(span);
        }
      }
    }

    assertFalse(setSpans.isEmpty(), "expected at least one SET command span");
    for (DDSpan span : setSpans) {
      assertEquals("SET", String.valueOf(span.getResourceName()));
      assertEquals("redis-client", String.valueOf(span.getTag(Tags.COMPONENT)));
      assertEquals("redis", span.getTag(Tags.DB_TYPE));
      assertNotNull(span.getTag(Tags.PEER_HOSTNAME), "command span should include peer.hostname");
      assertEquals(expectedNode.getUri().getHost(), span.getTag(Tags.PEER_HOSTNAME));
    }
  }
}
