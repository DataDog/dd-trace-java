package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SubscriptionTest extends AbstractInstrumentationTest {

  @Test
  void maybeSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Maybe<Connection> connection = Maybe.create(emitter -> emitter.onSuccess(new Connection()));
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
  void singleSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Single<Connection> connection = Single.create(emitter -> emitter.onSuccess(new Connection()));
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
  void completableSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Completable action = Completable.create(emitter -> emitter.onComplete());
      action.subscribe(
          () -> {
            new Connection().query();
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
  void observableSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Observable<Connection> connection =
          Observable.create(
              emitter -> {
                emitter.onNext(new Connection());
                emitter.onComplete();
              });
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
  void flowableSubscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Flowable<Connection> connection =
          Flowable.create(
              emitter -> {
                emitter.onNext(new Connection());
                emitter.onComplete();
              },
              BackpressureStrategy.BUFFER);
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

  static class Connection {
    int query() {
      AgentSpan span = startSpan("test", "Connection.query");
      span.finish();
      return new Random().nextInt();
    }
  }
}
