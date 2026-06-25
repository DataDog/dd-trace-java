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
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests that context propagation works for all five RxJava 3 reactive types (Observable, Flowable,
 * Single, Maybe, Completable) by verifying that a child span created inside a subscribe callback is
 * correctly parented to the span that was active when the subscription was created.
 */
public class RxJava3SubscriptionTest extends AbstractInstrumentationTest {

  /** Simulates a traced operation that could happen inside a subscriber callback. */
  static int tracedQuery() {
    AgentSpan span = startSpan("test", "Connection.query");
    span.finish();
    return new Random().nextInt();
  }

  /** Simulates a traced void operation for Completable callbacks. */
  static void tracedAction() {
    AgentSpan span = startSpan("test", "Connection.query");
    span.finish();
  }

  @Test
  void maybeSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Maybe.create(
              emitter -> {
                emitter.onSuccess(new Object());
              })
          .subscribe(
              value -> {
                tracedQuery();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }

  @Test
  void observableSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Observable.just(1)
          .subscribe(
              value -> {
                tracedQuery();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }

  @Test
  void flowableSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Flowable.just(1)
          .subscribe(
              value -> {
                tracedQuery();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }

  @Test
  void singleSubscribeLambdaRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Single.just(1)
          .subscribe(
              value -> {
                tracedQuery();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }

  @Test
  void completableSubscribeRunsUnderParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Completable.fromRunnable(() -> {})
          .subscribe(
              () -> {
                tracedAction();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }

  @Test
  void contextPropagatesAcrossThreadBoundary() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Maybe.create(
              emitter -> {
                emitter.onSuccess(42);
              })
          .subscribeOn(Schedulers.io())
          .subscribe(
              value -> {
                tracedQuery();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }

  @Test
  void multipleSubscribersEachCaptureOwnParent() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(2);

    // First subscriber under parent-1
    AgentSpan parent1 = startSpan("test", "parent-1");
    AgentScope scope1 = activateSpan(parent1);
    Maybe<Integer> maybe1 = Maybe.just(1);
    maybe1.subscribe(
        value -> {
          AgentSpan child = startSpan("test", "child-1");
          child.finish();
          latch.countDown();
        });
    scope1.close();
    parent1.finish();

    // Second subscriber under parent-2
    AgentSpan parent2 = startSpan("test", "parent-2");
    AgentScope scope2 = activateSpan(parent2);
    Maybe<Integer> maybe2 = Maybe.just(2);
    maybe2.subscribe(
        value -> {
          AgentSpan child = startSpan("test", "child-2");
          child.finish();
          latch.countDown();
        });
    scope2.close();
    parent2.finish();

    latch.await(5, TimeUnit.SECONDS);

    // Two independent traces, each with a parent-child pair
    assertTraces(
        trace(
            span().root().operationName("parent-1").resourceName("parent-1").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("child-1")
                .resourceName("child-1")
                .tags(defaultTags())),
        trace(
            span().root().operationName("parent-2").resourceName("parent-2").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("child-2")
                .resourceName("child-2")
                .tags(defaultTags())));
  }

  @Test
  void errorInCallbackDoesNotBreakContextPropagation() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Observable.just(1, 2)
          .subscribe(
              value -> {
                if (value == 1) {
                  throw new RuntimeException("callback error");
                }
                // This should not be reached since error terminates the stream,
                // but the span created before the error should still have the correct parent
                tracedQuery();
                latch.countDown();
              },
              error -> {
                // Error handler — context should still be properly propagated
                tracedQuery();
                latch.countDown();
              });
    } finally {
      scope.close();
      parent.finish();
    }
    latch.await(5, TimeUnit.SECONDS);

    // The error handler creates a child span under the parent
    assertTraces(
        trace(
            span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .tags(defaultTags())));
  }
}
