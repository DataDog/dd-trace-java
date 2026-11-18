import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDTags.ERROR_MSG;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.AGENT_CRASHING_COMMAND_PREFIX;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.test.junit.utils.config.WithConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

abstract class Lettuce5SyncClientTest extends Lettuce5ClientTestBase {
  @Test
  @DisplayName("connect")
  void connect() {
    RedisClient testConnectionClient = RedisClient.create(embeddedDbUri);
    testConnectionClient.setOptions(CLIENT_OPTIONS);

    try (StatefulRedisConnection<String, String> testConnection = testConnectionClient.connect()) {
      assertTraces(trace(redisSpan("CONNECT:" + dbAddr)));
    }
  }

  @Test
  @DisplayName("connect exception")
  void connectException() {
    RedisClient testConnectionClient = RedisClient.create(dbUriNonExistent);
    testConnectionClient.setOptions(CLIENT_OPTIONS);

    assertThrows(RedisConnectionException.class, testConnectionClient::connect);

    assertTraces(
        trace(
            redisSpan(
                "CONNECT:" + dbAddrNonExistent,
                incorrectPort,
                true,
                false,
                error(CompletionException.class),
                tag(ERROR_MSG, validates(value -> value instanceof String)))));
  }

  @Test
  @DisplayName("set command")
  void setCommand() {
    String result = syncCommands.set("TESTSETKEY", "TESTSETVAL");

    assertEquals("OK", result);
    assertTraces(trace(redisSpan("SET")));
  }

  @Test
  @DisplayName("get command")
  void getCommand() {
    String result = syncCommands.get("TESTKEY");

    assertEquals("TESTVAL", result);
    assertTraces(trace(redisSpan("GET")));
  }

  @Test
  @DisplayName("get non existent key command")
  void getNonExistentKey() {
    String result = syncCommands.get("NON_EXISTENT_KEY");

    assertNull(result);
    assertTraces(trace(redisSpan("GET")));
  }

  @Test
  @DisplayName("command with no arguments")
  void commandWithNoArguments() {
    String keyRetrieved = syncCommands.randomkey();

    assertNotNull(keyRetrieved);
    assertTraces(trace(redisSpan("RANDOMKEY")));
  }

  @Test
  @DisplayName("list command")
  void listCommand() {
    long result = syncCommands.lpush("TESTLIST", "TESTLIST ELEMENT");

    assertEquals(1L, result);
    assertTraces(trace(redisSpan("LPUSH")));
  }

  @Test
  @DisplayName("hash set command")
  void hashSetCommand() {
    String result = syncCommands.hmset("user", testHashMap);

    assertEquals("OK", result);
    assertTraces(trace(redisSpan("HMSET")));
  }

  @Test
  @DisplayName("hash getall command")
  void hashGetAllCommand() {
    Map<String, String> result = syncCommands.hgetall("TESTHM");

    assertEquals(testHashMap, result);
    assertTraces(trace(redisSpan("HGETALL")));
  }

  @Test
  @DisplayName("debug segfault command (returns void) with no argument should produce span")
  void debugSegfaultCommand() {
    syncCommands.debugSegfault();

    assertTraces(trace(redisSpan(AGENT_CRASHING_COMMAND_PREFIX + "DEBUG")));
  }

  @Test
  @DisplayName("shutdown command (returns void) should produce a span")
  void shutdownCommand() {
    syncCommands.shutdown(false);

    assertTraces(trace(redisSpan("SHUTDOWN")));
  }
}

class Lettuce5SyncClientV0Test extends Lettuce5SyncClientTest {}

@WithConfig(key = TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v1")
class Lettuce5SyncClientV1ForkedTest extends Lettuce5SyncClientTest {
  @Override
  int namingVersion() {
    return 1;
  }
}

@WithConfig(key = PROFILING_ENABLED, value = "true")
class Lettuce5SyncClientProfilingForkedTest extends Lettuce5SyncClientTest {}
