package testdog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.TopicProcessor;
import reactor.core.publisher.WorkQueueProcessor;

class SubscriptionTest extends AbstractInstrumentationTest {

  static class Connection {
    static int query() {
      AgentSpan span = startSpan("test", "Connection.query");
      span.finish();
      return new Random().nextInt();
    }
  }

  // --- Processor-based subscription tests ----------------------------------

  @Test
  void directProcessorSingleSubscriberPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(DirectProcessor.create(), 1);
  }

  @Test
  void emitterProcessorSingleSubscriberPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(EmitterProcessor.create(), 1);
  }

  @Test
  void topicProcessorSingleSubscriberPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(TopicProcessor.create(), 1);
  }

  @Test
  void workQueueProcessorSingleSubscriberPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(WorkQueueProcessor.create(), 1);
  }

  @Test
  void directProcessorMultipleSubscribersPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(DirectProcessor.create(), 3);
  }

  @Test
  void emitterProcessorMultipleSubscribersPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(EmitterProcessor.create(), 3);
  }

  @Test
  void topicProcessorMultipleSubscribersPropagatesParentSpan() throws InterruptedException {
    verifyProcessorPropagation(TopicProcessor.create(), 3);
  }

  @SuppressWarnings("unchecked")
  private void verifyProcessorPropagation(Object rawProcessor, int consumers)
      throws InterruptedException {
    FluxProcessor<Connection, Connection> processor =
        (FluxProcessor<Connection, Connection>) rawProcessor;
    CountDownLatch published = new CountDownLatch(consumers);
    CountDownLatch subscribed = new CountDownLatch(consumers);

    for (int i = 0; i < consumers; i++) {
      Thread t =
          new Thread(
              () -> {
                AgentSpan parent = startSpan("test", "parent");
                try (AgentScope scope = activateSpan(parent)) {
                  processor.subscribe(
                      connection -> {
                        Connection.query();
                        published.countDown();
                      });
                  subscribed.countDown();
                  try {
                    published.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                } finally {
                  parent.finish();
                }
              });
      t.start();
    }

    subscribed.await();
    processor.sink().next(new Connection());
    published.await();

    // Each subscriber gets its own trace with parent -> child relationship
    TraceMatcher[] traceMatchers = new TraceMatcher[consumers];
    for (int i = 0; i < consumers; i++) {
      traceMatchers[i] =
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("Connection.query"));
    }
    assertTraces(traceMatchers);
  }

  // --- Broadcasting flux ---------------------------------------------------

  @Test
  void broadcastingFluxPropagatesParentSpan() throws InterruptedException {
    Flux<Connection> connection = Flux.<Connection>just(new Connection()).publish().autoConnect();

    CountDownLatch published = new CountDownLatch(1);
    CountDownLatch subscribed = new CountDownLatch(1);

    Thread t =
        new Thread(
            () -> {
              AgentSpan parent = startSpan("test", "parent");
              try (AgentScope scope = activateSpan(parent)) {
                connection.subscribe(
                    c -> {
                      Connection.query();
                      published.countDown();
                    });
                subscribed.countDown();
                try {
                  published.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } finally {
                parent.finish();
              }
            });
    t.start();
    subscribed.await();
    published.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("Connection.query")));
  }

  // --- Mono subscription propagates parent span ----------------------------

  @Test
  void monoSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono<Connection> connection = Mono.create(emitter -> emitter.success(new Connection()));
      connection.subscribe(
          c -> {
            Connection.query();
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

  // --- Flux subscription propagates parent span ----------------------------

  @Test
  void fluxSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flux<Connection> connection =
          Flux.create(
              emitter -> {
                emitter.next(new Connection());
                emitter.complete();
              });
      connection.subscribe(
          c -> {
            Connection.query();
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

  // --- Mono then() propagates context across chained publishers ------------

  @Test
  void monoThenPropagatesContext() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono.create(
              sink -> {
                AgentSpan child1 = startSpan("test", "child1");
                child1.finish();
                sink.success();
              })
          .then(
              Mono.create(
                  sink -> {
                    AgentSpan child2 = startSpan("test", "child2");
                    child2.finish();
                    sink.success();
                  }))
          .block();
    } finally {
      parent.finish();
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child1"),
            span().childOfIndex(0).operationName("child2")));
  }

  // --- Mono lifecycle spans without parent produce separate traces ---------

  @Test
  void monoLifecycleSpansWithoutParentProduceSeparateTraces() {
    Mono.fromCallable(
            () -> {
              AgentSpan span = startSpan("test", "span");
              span.finish();
              return Mono.just("Hello World");
            })
        .doOnNext(
            v -> {
              AgentSpan onNext = startSpan("test", "onNext");
              onNext.finish();
            })
        .doFinally(
            signal -> {
              AgentSpan finallySpan = startSpan("test", "finally");
              finallySpan.finish();
            })
        .doAfterTerminate(
            () -> {
              AgentSpan after = startSpan("test", "after");
              after.finish();
            })
        .block();

    // Without a parent span, each manually started span is its own trace
    assertTraces(
        trace(span().root().operationName("span")),
        trace(span().root().operationName("onNext")),
        trace(span().root().operationName("after")),
        trace(span().root().operationName("finally")));
  }

  // --- Mono lifecycle spans with parent all join the parent trace ----------

  @Test
  void monoLifecycleSpansWithParentJoinParentTrace() {
    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Mono.fromCallable(
              () -> {
                AgentSpan span = startSpan("test", "span");
                span.finish();
                return Mono.just("Hello World");
              })
          .doOnNext(
              v -> {
                AgentSpan onNext = startSpan("test", "onNext");
                onNext.finish();
              })
          .doFinally(
              signal -> {
                AgentSpan finallySpan = startSpan("test", "finally");
                finallySpan.finish();
              })
          .doAfterTerminate(
              () -> {
                AgentSpan after = startSpan("test", "after");
                after.finish();
              })
          .block();
    } finally {
      parent.finish();
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("span"),
            span().childOfIndex(0).operationName("onNext"),
            span().childOfIndex(0).operationName("after"),
            span().childOfIndex(0).operationName("finally")));
  }
}
