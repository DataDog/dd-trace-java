import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.AGENT_CRASHING_COMMAND_PREFIX;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

abstract class Lettuce5ReactiveClientTest extends Lettuce5ClientTestBase {
  @Test
  @DisplayName("set command with subscribe on a defined consumer")
  void setCommandWithDefinedConsumer() throws Exception {
    CompletableFuture<String> result = new CompletableFuture<>();
    Consumer<String> consumer = result::complete;

    reactiveCommands
        .set("TESTSETKEY", "TESTSETVAL")
        .subscribe(consumer, result::completeExceptionally);

    assertEquals("OK", result.get(1, SECONDS));
    assertTraces(trace(redisSpan("SET")));
  }

  @Test
  @DisplayName("get command with lambda function")
  void getCommandWithLambda() throws Exception {
    CompletableFuture<String> result = new CompletableFuture<>();

    reactiveCommands
        .get("TESTKEY")
        .subscribe(value -> result.complete(value), result::completeExceptionally);

    assertEquals("TESTVAL", result.get(1, SECONDS));
    assertTraces(trace(redisSpan("GET")));
  }

  // to make sure instrumentation's chained completion stages won't interfere with user's, while
  // still
  // recording metrics
  @Test
  @DisplayName("get non existent key command")
  void getNonExistentKey() throws Exception {
    CompletableFuture<String> result = new CompletableFuture<>();
    String defaultValue = "NOT THIS VALUE";

    reactiveCommands
        .get("NON_EXISTENT_KEY")
        .defaultIfEmpty(defaultValue)
        .subscribe(value -> result.complete(value), result::completeExceptionally);

    assertEquals(defaultValue, result.get(1, SECONDS));
    assertTraces(trace(redisSpan("GET")));
  }

  @Test
  @DisplayName("command with no arguments")
  void commandWithNoArguments() throws Exception {
    CompletableFuture<String> result = new CompletableFuture<>();

    reactiveCommands
        .randomkey()
        .subscribe(value -> result.complete(value), result::completeExceptionally);

    String key = result.get(1, SECONDS);
    assertTrue("TESTKEY".equals(key) || "TESTHM".equals(key));
    assertTraces(trace(redisSpan("RANDOMKEY")));
  }

  @Test
  @DisplayName("command flux publisher")
  void commandFluxPublisher() {
    reactiveCommands.command().subscribe();

    assertTraces(
        trace(redisSpan(AGENT_CRASHING_COMMAND_PREFIX + "COMMAND", nonEmptyCommandResults())));
  }

  @Test
  @DisplayName("command cancel after 2 on flux publisher")
  void commandFluxPublisherCancelledAfterTwoResults() {
    reactiveCommands.command().take(2).subscribe();

    assertTraces(
        trace(
            redisSpan(
                AGENT_CRASHING_COMMAND_PREFIX + "COMMAND",
                tag("db.command.cancelled", is(true)),
                tag("db.command.results.count", is(2)))));
  }

  @Test
  @DisplayName("non reactive command should not produce span")
  void nonReactiveCommandProducesNoSpan() {
    String digest = reactiveCommands.digest("return 1");

    assertNotNull(digest);
    assertEquals(0, writer.size());
  }

  @Test
  @DisplayName("debug segfault command (returns mono void) with no argument should produce span")
  void debugSegfaultCommand() {
    reactiveCommands.debugSegfault().subscribe();

    assertTraces(trace(redisSpan(AGENT_CRASHING_COMMAND_PREFIX + "DEBUG")));
  }

  @Test
  @DisplayName("shutdown command (returns void) with argument should produce span")
  void shutdownCommand() {
    reactiveCommands.shutdown(false).subscribe();

    assertTraces(trace(redisSpan("SHUTDOWN")));
  }

  @Test
  @DisplayName("blocking subscriber")
  void blockingSubscriber() {
    runUnderTrace(
        "test-parent",
        () -> {
          reactiveCommands
              .set("a", "1")
              .then(reactiveCommands.get("a")) // The get here is ending up in another trace
              .block();
          reactiveCommands.command().then(reactiveCommands.get("a"));
        });

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            parentSpan("test-parent"),
            redisChildSpan("SET"),
            redisChildSpan("GET")));
  }

  @Test
  @DisplayName("blocking subscriber on flux")
  void blockingSubscriberOnFlux() {
    runUnderTrace(
        "test-parent", () -> reactiveCommands.command().then(reactiveCommands.get("a")).block());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            parentSpan("test-parent"),
            redisChildSpan(AGENT_CRASHING_COMMAND_PREFIX + "COMMAND", nonEmptyCommandResults()),
            redisChildSpan("GET")));
  }

  @Test
  @DisplayName("async subscriber")
  void asyncSubscriber() {
    runUnderTrace(
        "test-parent",
        () -> {
          reactiveCommands
              .set("a", "1")
              .then(reactiveCommands.get("a")) // The get here is reported separately
              .subscribe();
          blockUntilChildSpansFinished(2);
        });

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            parentSpan("test-parent"),
            redisChildSpan("SET"),
            redisChildSpan("GET")));
  }

  @Test
  @DisplayName("async subscriber with specific thread pool")
  void asyncSubscriberWithSpecificThreadPool() {
    runUnderTrace(
        "test-parent",
        () -> {
          reactiveCommands
              .set("a", "1")
              .then(reactiveCommands.get("a"))
              .subscribeOn(Schedulers.newParallel("test"))
              .subscribe();
          blockUntilChildSpansFinished(2);
        });

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            parentSpan("test-parent"),
            redisChildSpan("SET"),
            redisChildSpan("GET")));
  }

  private static TagsMatcher nonEmptyCommandResults() {
    return tag("db.command.results.count", validates((Number count) -> count.longValue() > 0));
  }
}

class Lettuce5ReactiveClientV0Test extends Lettuce5ReactiveClientTest {}

@WithConfig(key = TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v1")
class Lettuce5ReactiveClientV1ForkedTest extends Lettuce5ReactiveClientTest {
  @Override
  int namingVersion() {
    return 1;
  }
}

@WithConfig(key = PROFILING_ENABLED, value = "true")
class Lettuce5ReactiveClientProfilingForkedTest extends Lettuce5ReactiveClientTest {}
