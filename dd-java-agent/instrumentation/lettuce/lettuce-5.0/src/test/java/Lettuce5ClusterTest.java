import static datadog.trace.agent.test.utils.PortUtils.randomOpenPort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.test.util.PollingConditions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class Lettuce5ClusterTest extends AbstractInstrumentationTest {
  private static final String TEST_SET_KEY = "TESTSETKEY";
  private static final String TEST_SET_VALUE = "TESTSETVAL";
  private static final int MAX_TCP_PORT = 65535;
  private static final int CLUSTER_BUS_PORT_OFFSET = 10000;
  private static final int MAX_CLUSTER_DATA_PORT = MAX_TCP_PORT - CLUSTER_BUS_PORT_OFFSET;

  private GenericContainer<?> redisCluster;
  private RedisClusterClient redisClient;
  private StatefulRedisClusterConnection<String, String> connection;
  private int redisClusterMasterPort;
  private int redisClusterReplicaPort;

  @BeforeEach
  void setUpRedis() throws Exception {
    redisClusterMasterPort = randomClusterPort(MAX_CLUSTER_DATA_PORT);
    redisClusterReplicaPort = randomClusterPort(MAX_CLUSTER_DATA_PORT);
    while (redisClusterMasterPort == redisClusterReplicaPort
        || redisClusterMasterPort + CLUSTER_BUS_PORT_OFFSET == redisClusterReplicaPort
        || redisClusterReplicaPort + CLUSTER_BUS_PORT_OFFSET == redisClusterMasterPort) {
      redisClusterReplicaPort = randomClusterPort(MAX_CLUSTER_DATA_PORT);
    }

    // Redis cluster discovery returns the announced node port, so the host-side port must be
    // stable. Use the same random ports inside the container so cluster nodes can also reach each
    // other at their announced addresses.
    redisCluster = new GenericContainer<>("redis:6.2.6");
    redisCluster.setPortBindings(
        Arrays.asList(
            redisClusterMasterPort + ":" + redisClusterMasterPort,
            redisClusterReplicaPort + ":" + redisClusterReplicaPort));
    redisCluster
        .withExposedPorts(redisClusterMasterPort, redisClusterReplicaPort)
        .withCommand(
            "sh", "-c", redisClusterCommand(redisClusterMasterPort, redisClusterReplicaPort))
        .waitingFor(Wait.forLogMessage(".*CLUSTER_READY.*\\n", 1));
    redisCluster.start();

    RedisURI redisURI =
        RedisURI.Builder.redis(redisCluster.getHost(), redisClusterMasterPort).build();
    redisClient = RedisClusterClient.create(redisURI);
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
      redisCluster.close();
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

  @Test
  void clusterReadCommandSpanUsesReplicaPeerWithReadFromReplica() throws Exception {
    assertEquals("OK", connection.sync().set(TEST_SET_KEY, TEST_SET_VALUE));
    connection.setReadFrom(ReadFrom.SLAVE);
    new PollingConditions(30)
        .delay(0.5)
        .eventually(
            () -> {
              redisClient.reloadPartitions();
              assertEquals(TEST_SET_VALUE, connection.sync().get(TEST_SET_KEY));
            });

    blockUntilTracesMatch(traces -> !findCommandSpans(traces, "GET").isEmpty());
    tracer.flush();
    writer.clear();

    String result = connection.sync().get(TEST_SET_KEY);

    assertEquals(TEST_SET_VALUE, result);
    assertGetSpanHasReplicaPeer();
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

  private void assertGetSpanHasReplicaPeer() {
    blockUntilTracesMatch(traces -> !findCommandSpans(traces, "GET").isEmpty());

    RedisClusterNode master =
        connection.getPartitions().getPartitionBySlot(SlotHash.getSlot(TEST_SET_KEY));
    assertNotNull(master, "expected a cluster master node for the command key slot");

    RedisClusterNode replica = findReplicaOf(master);
    assertNotNull(replica, "expected a replica for the command key slot");
    assertNotEquals(
        master.getUri().getPort(),
        replica.getUri().getPort(),
        "test must use different master and replica endpoints");

    List<DDSpan> getSpans = findCommandSpans(writer, "GET");
    assertFalse(getSpans.isEmpty(), "expected at least one GET command span");
    for (DDSpan span : getSpans) {
      assertEquals("GET", String.valueOf(span.getResourceName()));
      assertEquals("redis-client", String.valueOf(span.getTag(Tags.COMPONENT)));
      assertEquals("redis", span.getTag(Tags.DB_TYPE));
      assertEquals(replica.getUri().getPort(), span.getTag(Tags.PEER_PORT));
      assertEquals(replica.getUri().getHost(), span.getTag(Tags.PEER_HOSTNAME));
    }
  }

  private RedisClusterNode findReplicaOf(RedisClusterNode master) {
    for (RedisClusterNode node : connection.getPartitions()) {
      if (master.getNodeId().equals(node.getSlaveOf())) {
        return node;
      }
    }
    fail("No replica found for master " + master);
    return null;
  }

  private static List<DDSpan> findCommandSpans(Iterable<List<DDSpan>> traces, String command) {
    List<DDSpan> commandSpans = new ArrayList<>();
    for (List<DDSpan> trace : traces) {
      for (DDSpan span : trace) {
        if (command.contentEquals(span.getResourceName())
            && "redis-client".equals(String.valueOf(span.getTag(Tags.COMPONENT)))) {
          commandSpans.add(span);
        }
      }
    }
    return commandSpans;
  }

  private static int randomClusterPort(int maxPort) {
    int port = randomOpenPort();
    while (port > maxPort) {
      port = randomOpenPort();
    }
    return port;
  }

  private static String redisClusterCommand(int masterPort, int replicaPort) {
    return "set -e; "
        + "mkdir -p /tmp/redis-cluster; "
        // Start the slot-owning master on its announced data and cluster bus ports.
        + "redis-server --port "
        + masterPort
        + " --dir /tmp/redis-cluster --cluster-enabled yes --cluster-config-file nodes-"
        + masterPort
        + ".conf --cluster-node-timeout 5000 --appendonly no --protected-mode no"
        + " --cluster-announce-ip 127.0.0.1 --cluster-announce-port "
        + masterPort
        + " --cluster-announce-bus-port "
        + (masterPort + CLUSTER_BUS_PORT_OFFSET)
        + " --daemonize yes; "
        // Start the replica on its own announced data and cluster bus ports.
        + "redis-server --port "
        + replicaPort
        + " --dir /tmp/redis-cluster --cluster-enabled yes --cluster-config-file nodes-"
        + replicaPort
        + ".conf --cluster-node-timeout 5000 --appendonly no --protected-mode no"
        + " --cluster-announce-ip 127.0.0.1 --cluster-announce-port "
        + replicaPort
        + " --cluster-announce-bus-port "
        + (replicaPort + CLUSTER_BUS_PORT_OFFSET)
        + " --daemonize yes; "
        // Wait until both Redis server processes accept commands.
        + "until redis-cli -p "
        + masterPort
        + " ping; do sleep 0.1; done; "
        + "until redis-cli -p "
        + replicaPort
        + " ping; do sleep 0.1; done; "
        // Assign every slot to one master so the test cluster is valid with a single shard.
        + "redis-cli -p "
        + masterPort
        + " cluster addslots $(seq 0 16383); "
        // Introduce the replica node to the master's cluster view.
        + "redis-cli -p "
        + replicaPort
        + " cluster meet 127.0.0.1 "
        + masterPort
        + "; "
        // Capture stable node IDs needed for replication checks.
        + "master_id=$(redis-cli -p "
        + masterPort
        + " cluster myid); "
        + "replica_id=$(redis-cli -p "
        + replicaPort
        + " cluster myid); "
        // Wait until the replica sees the master before requesting replication.
        + "until redis-cli -p "
        + replicaPort
        + " cluster nodes | grep \"$master_id\"; do sleep 0.1; done; "
        // Convert the second node into a replica of the slot-owning master.
        + "redis-cli -p "
        + replicaPort
        + " cluster replicate \"$master_id\"; "
        // Wait until the master's cluster view records the replica relationship.
        + "until redis-cli -p "
        + masterPort
        + " cluster nodes | grep \"$replica_id\" | grep \"$master_id\" | grep -q slave; do sleep 0.1; done; "
        // Wait until the replica's local cluster view records its replica role.
        + "until redis-cli -p "
        + replicaPort
        + " cluster nodes | grep \"$replica_id\" | grep \"$master_id\" | grep -q myself,slave; do sleep 0.1; done; "
        // Wait until Redis reports the process role as replica.
        + "until redis-cli -p "
        + replicaPort
        + " role | grep -q slave; do sleep 0.1; done; "
        // Wait until the cluster is usable before releasing the Testcontainers wait strategy.
        + "until redis-cli -p "
        + masterPort
        + " cluster info | grep -q cluster_state:ok; do sleep 0.1; done; "
        // Signal readiness to the Java test.
        + "echo CLUSTER_READY; "
        // Keep the container alive for the duration of the test.
        + "tail -f /dev/null";
  }
}
