package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests that RxJava 3 context propagation correctly bridges parent-child span relationships across
 * subscribe boundaries. The instrumentation creates no spans of its own — it only ensures the trace
 * context active at subscribe time is restored inside subscriber callbacks.
 */
class RxJava3SubscriptionTest extends AbstractInstrumentationTest {

  @Test
  void maybeSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      Maybe.create(
              emitter -> {
                emitter.onSuccess(new Connection());
              })
          .subscribe(
              connection -> {
                ((Connection) connection).query();
                latch.countDown();
              });
    } finally {
      parentScope.close();
      parentSpan.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("parent").root().tags(defaultTags()),
            span().operationName("Connection.query").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void singleSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      Single.create(
              emitter -> {
                emitter.onSuccess(new Connection());
              })
          .subscribe(
              connection -> {
                ((Connection) connection).query();
                latch.countDown();
              });
    } finally {
      parentScope.close();
      parentSpan.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("parent").root().tags(defaultTags()),
            span().operationName("Connection.query").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void observableSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      Observable.create(
              emitter -> {
                emitter.onNext(new Connection());
                emitter.onComplete();
              })
          .subscribe(
              connection -> {
                ((Connection) connection).query();
                latch.countDown();
              });
    } finally {
      parentScope.close();
      parentSpan.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("parent").root().tags(defaultTags()),
            span().operationName("Connection.query").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void flowableSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      Flowable.just(new Connection())
          .subscribe(
              connection -> {
                connection.query();
                latch.countDown();
              });
    } finally {
      parentScope.close();
      parentSpan.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("parent").root().tags(defaultTags()),
            span().operationName("Connection.query").childOfIndex(0).tags(defaultTags())));
  }

  @Test
  void completableSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      Completable.create(
              emitter -> {
                Connection connection = new Connection();
                connection.query();
                emitter.onComplete();
              })
          .subscribe(latch::countDown);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("parent").root().tags(defaultTags()),
            span().operationName("Connection.query").childOfIndex(0).tags(defaultTags())));
  }

  static class Connection {
    static int query() {
      AgentSpan span = startSpan("test", "Connection.query");
      span.finish();
      return new Random().nextInt();
    }
  }
}
