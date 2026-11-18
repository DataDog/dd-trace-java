import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDTags.ERROR_MSG;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.AGENT_CRASHING_COMMAND_PREFIX;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.AsyncCommand;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

abstract class Lettuce5AsyncClientTest extends Lettuce5ClientTestBase {
  @Test
  @DisplayName("connect using get on ConnectionFuture")
  void connectUsingFutureGet() throws Exception {
    RedisClient testConnectionClient = RedisClient.create(embeddedDbUri);
    testConnectionClient.setOptions(CLIENT_OPTIONS);

    ConnectionFuture<StatefulRedisConnection<String, String>> connectionFuture =
        testConnectionClient.connectAsync(
            StringCodec.UTF8, new RedisURI(redisServer.getHost(), port, Duration.ofSeconds(3)));
    try (StatefulRedisConnection<String, String> testConnection = connectionFuture.get()) {
      writer.waitForTraces(1);

      assertNotNull(testConnection);
      assertTraces(trace(redisSpan("CONNECT:" + dbAddr)));
    }
  }

  @Test
  @DisplayName("connect exception inside the connection future")
  void connectionFutureException() {
    RedisClient testConnectionClient = RedisClient.create(dbUriNonExistent);
    testConnectionClient.setOptions(CLIENT_OPTIONS);

    ConnectionFuture<StatefulRedisConnection<String, String>> connectionFuture =
        testConnectionClient.connectAsync(
            StringCodec.UTF8,
            new RedisURI(redisServer.getHost(), incorrectPort, Duration.ofSeconds(3)));
    assertThrows(ExecutionException.class, connectionFuture::get);

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
  @DisplayName("set command using Future get with timeout")
  void setCommandWithTimeout() throws Exception {
    RedisFuture<String> redisFuture = asyncCommands.set("TESTSETKEY", "TESTSETVAL");
    String result = redisFuture.get(3, SECONDS);

    assertEquals("OK", result);
    assertTraces(trace(redisSpan("SET")));
  }

  @Test
  @DisplayName("get command chained with thenAccept")
  void getCommandChainedWithThenAccept() throws Exception {
    Consumer<String> consumer = result -> assertEquals("TESTVAL", result);

    RedisFuture<String> redisFuture = asyncCommands.get("TESTKEY");
    redisFuture.thenAccept(consumer).toCompletableFuture().get(1, SECONDS);

    assertTraces(trace(redisSpan("GET")));
  }

  // to make sure instrumentation's chained completion stages won't interfere with user's, while
  // still
  // recording metrics
  @Test
  @DisplayName("get non existent key command with handleAsync and chained with thenApply")
  void missingKeyWithHandleAsyncAndThenApply() throws Exception {
    String success = "KEY MISSING";

    RedisFuture<String> redisFuture = asyncCommands.get("NON_EXISTENT_KEY");
    redisFuture
        .handleAsync((result, failure) -> {
          assertNull(result);
          assertNull(failure);
          return result == null ? success : result;
        })
        .thenApply(result -> {
          assertEquals(success, result);
          return null;
        })
        .toCompletableFuture()
        .get(1, SECONDS);

    assertTraces(trace(redisSpan("GET")));
  }

  @Test
  @DisplayName("command with no arguments using a biconsumer")
  void commandWithNoArgumentsUsingBiConsumer() throws Exception {
    RedisFuture<String> redisFuture = asyncCommands.randomkey();
    redisFuture
        .whenCompleteAsync((keyRetrieved, failure) -> assertNotNull(keyRetrieved))
        .toCompletableFuture()
        .get(1, SECONDS);

    assertTraces(trace(redisSpan("RANDOMKEY")));
  }

  @Test
  @DisplayName("hash set and then nest apply to hash getall")
  void hashSetThenNestedHashGetAll() throws Exception {
    CompletableFuture<Map<String, String>> nestedResult = new CompletableFuture<>();
    RedisFuture<String> hashSet = asyncCommands.hmset("TESTHM", testHashMap);
    Map<String, String> result =
        hashSet
            .thenApplyAsync(setResult -> {
              // Wait for the HMSET trace to get written.
              try {
                writer.waitForTraces(1);
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                nestedResult.completeExceptionally(failure);
                return null;
              } catch (TimeoutException failure) {
                nestedResult.completeExceptionally(failure);
                return null;
              }
              assertEquals("OK", setResult);
              RedisFuture<Map<String, String>> hashGetAll = asyncCommands.hgetall("TESTHM");
              hashGetAll.exceptionally(failure -> {
                nestedResult.completeExceptionally(failure);
                return null;
              });
              hashGetAll.thenAccept(nestedResult::complete);
              return null;
            })
            .thenCompose(ignored -> nestedResult)
            .toCompletableFuture()
            .get(1, SECONDS);

    assertEquals(testHashMap, result);
    assertTraces(trace(redisSpan("HMSET")), trace(redisSpan("HGETALL")));
  }

  @Test
  @DisplayName("command completes exceptionally")
  void commandCompletesExceptionally() throws Exception {
    // Turn off auto flush to complete the command exceptionally manually.
    connection.setAutoFlushCommands(false);
    RedisFuture<Long> redisFuture = asyncCommands.del("key1", "key2");
    boolean completedExceptionally =
        ((AsyncCommand<?, ?, ?>) redisFuture)
            .completeExceptionally(new IllegalStateException("TestException"));
    CompletableFuture<Throwable> callbackFailure = new CompletableFuture<>();
    redisFuture.exceptionally(failure -> {
      callbackFailure.complete(failure);
      throw new CompletionException(failure);
    });

    // Now flush and execute the command.
    connection.flushCommands();
    connection.setAutoFlushCommands(true);
    assertThrows(Exception.class, redisFuture::get);

    Throwable failure = callbackFailure.get(1, SECONDS);
    assertInstanceOf(IllegalStateException.class, failure);
    assertEquals("TestException", failure.getMessage());
    assertTrue(completedExceptionally);
    assertTraces(
        trace(
            redisSpan(
                "DEL", port, true, false, error(IllegalStateException.class, "TestException"))));
  }

  @Test
  @DisplayName("cancel command before it finishes")
  void cancelCommandBeforeItFinishes() throws Exception {
    connection.setAutoFlushCommands(false);
    RedisFuture<Long> redisFuture = asyncCommands.sadd("SKEY", "1", "2");
    CompletableFuture<Throwable> callbackFailure = new CompletableFuture<>();
    redisFuture.whenCompleteAsync((result, failure) -> callbackFailure.complete(failure));

    boolean cancelSuccess = redisFuture.cancel(true);
    connection.flushCommands();
    connection.setAutoFlushCommands(true);

    assertInstanceOf(CancellationException.class, callbackFailure.get(1, SECONDS));
    assertTrue(cancelSuccess);
    assertTraces(trace(redisSpan("SADD", tag("db.command.cancelled", is(true)))));
  }

  @Test
  @DisplayName("debug segfault command (returns void) with no argument should produce span")
  void debugSegfaultCommand() {
    asyncCommands.debugSegfault();

    assertTraces(trace(redisSpan(AGENT_CRASHING_COMMAND_PREFIX + "DEBUG")));
  }

  @Test
  @DisplayName("shutdown command (returns void) should produce a span")
  void shutdownCommand() {
    asyncCommands.shutdown(false);

    assertTraces(trace(redisSpan("SHUTDOWN")));
  }
}

class Lettuce5AsyncClientV0Test extends Lettuce5AsyncClientTest {}

@WithConfig(key = TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v1")
class Lettuce5AsyncClientV1ForkedTest extends Lettuce5AsyncClientTest {
  @Override
  int namingVersion() {
    return 1;
  }
}

@WithConfig(key = PROFILING_ENABLED, value = "true")
class Lettuce5AsyncClientProfilingForkedTest extends Lettuce5AsyncClientTest {}
