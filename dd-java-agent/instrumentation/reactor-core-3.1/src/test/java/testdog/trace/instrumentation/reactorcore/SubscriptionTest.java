package testdog.trace.instrumentation.reactorcore;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SubscriptionTest extends AbstractInstrumentationTest {

  @Test
  void monoSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono<Connection> connection = Mono.create(sink -> sink.success(new Connection()));
      connection.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      parent.finish();
    }
    latch.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("Connection.query")));
  }

  @Test
  void fluxSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux<Connection> connections =
          Flux.create(
              emitter -> {
                emitter.next(new Connection());
                emitter.complete();
              });
      connections.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      parent.finish();
    }
    latch.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("Connection.query")));
  }

  @Test
  void monoJustSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono<Connection> connection = Mono.just(new Connection());
      connection.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      parent.finish();
    }
    latch.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("Connection.query")));
  }

  @Test
  void fluxFromIterableSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux<Connection> connections =
          Flux.fromIterable(java.util.Arrays.asList(new Connection(), new Connection()));
      connections.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      parent.finish();
    }
    latch.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("Connection.query"),
            span().childOfIndex(0).operationName("Connection.query")));
  }

  static class Connection {
    int query() {
      AgentSpan span = startSpan("test", "Connection.query");
      span.finish();
      return new Random().nextInt();
    }
  }
}
