import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.redis.testcontainers.RedisContainer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class Lettuce5MasterReplicaTest extends AbstractInstrumentationTest {
  private static final String MASTER_REPLICA_CLASS = "io.lettuce.core.masterreplica.MasterReplica";
  private static final String MASTER_SLAVE_CLASS = "io.lettuce.core.masterslave.MasterSlave";
  private static final int DB_INDEX = 0;
  private static final ClientOptions CLIENT_OPTIONS =
      ClientOptions.builder().autoReconnect(false).build();

  private RedisContainer redisServer;
  private RedisClient redisClient;
  private StatefulRedisConnection<String, String> connection;
  private String host;
  private int port;

  @BeforeEach
  void setUpRedis() throws Exception {
    redisServer =
        new RedisContainer(DockerImageName.parse("redis:6.2.6"))
            .waitingFor(Wait.forListeningPort());
    redisServer.start();

    host = redisServer.getHost();
    port = redisServer.getFirstMappedPort();

    RedisURI redisURI = RedisURI.Builder.redis(host, port).withDatabase(DB_INDEX).build();
    redisClient = RedisClient.create();
    redisClient.setOptions(CLIENT_OPTIONS);
    // Prefer the newer MasterReplica facade when this source is compiled for latestDepTest, but
    // resolve both APIs reflectively so the same test still compiles with the Lettuce 5.0 baseline
    // and can keep compiling if the deprecated MasterSlave facade disappears later.
    connection = connectMasterReplica(redisClient, redisURI);
    connection.sync().ping();

    writer.waitForTraces(2);
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

    if (redisServer != null) {
      redisServer.stop();
    }
  }

  @Test
  void staticMasterReplicaCommandSpanHasPeerHostname() throws Exception {
    String result = connection.sync().set("TESTSETKEY", "TESTSETVAL");

    assertEquals("OK", result);
    writer.waitForTraces(1);

    List<DDSpan> setSpans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("SET".contentEquals(span.getResourceName())
            && "redis-client".equals(String.valueOf(span.getTag(Tags.COMPONENT)))) {
          setSpans.add(span);
        }
      }
    }

    assertEquals(1, setSpans.size(), "expected exactly one SET command span");
    DDSpan span = setSpans.get(0);
    assertEquals("SET", String.valueOf(span.getResourceName()));
    assertEquals("redis-client", String.valueOf(span.getTag(Tags.COMPONENT)));
    assertEquals("redis", span.getTag(Tags.DB_TYPE));
    assertNotNull(span.getTag(Tags.PEER_HOSTNAME), "command span should include peer.hostname");
    assertEquals(host, span.getTag(Tags.PEER_HOSTNAME));
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<String, String> connectMasterReplica(
      RedisClient redisClient, RedisURI redisURI) throws Exception {
    Class<?> facade = masterReplicaFacade();
    Method connect =
        facade.getMethod("connect", RedisClient.class, RedisCodec.class, Iterable.class);
    try {
      return (StatefulRedisConnection<String, String>)
          connect.invoke(null, redisClient, StringCodec.UTF8, singletonList(redisURI));
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    }
  }

  private static Class<?> masterReplicaFacade() throws ClassNotFoundException {
    try {
      return Class.forName(MASTER_REPLICA_CLASS);
    } catch (ClassNotFoundException ignored) {
      return Class.forName(MASTER_SLAVE_CLASS);
    }
  }
}
