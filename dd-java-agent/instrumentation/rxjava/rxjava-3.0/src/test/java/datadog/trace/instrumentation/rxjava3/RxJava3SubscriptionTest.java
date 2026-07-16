package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
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
 * Context-propagation tests for RxJava 3 subscription.
 *
 * <p>Verifies that when a reactive type is subscribed to under an active span, the trace context is
 * propagated into the subscriber callbacks so that child spans created inside those callbacks
 * become children of the parent span.
 */
class RxJava3SubscriptionTest extends AbstractInstrumentationTest {

  @Test
  void maybeSubscriptionPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Maybe<Connection> connection =
              Maybe.create(emitter -> emitter.onSuccess(new Connection()));
          connection.subscribe(
              conn -> {
                conn.query();
                latch.countDown();
              });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void observableSubscriptionPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Observable.just(new Connection())
              .subscribe(
                  conn -> {
                    conn.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void singleSubscriptionPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Single.just(new Connection())
              .subscribe(
                  conn -> {
                    conn.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void flowableSubscriptionPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Flowable.just(new Connection())
              .subscribe(
                  conn -> {
                    conn.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void completableSubscriptionPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Completable.fromAction(
                  () -> {
                    new Connection().query();
                    latch.countDown();
                  })
              .subscribe();
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void contextPropagatesAcrossThreadBoundary() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Maybe.create(emitter -> emitter.onSuccess(new Connection()))
              .subscribeOn(Schedulers.io())
              .subscribe(
                  conn -> {
                    ((Connection) conn).query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void multipleSubscribersCaptureOwnParent() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);

    runUnderTrace(
        "parent",
        () -> {
          Maybe<Connection> maybe = Maybe.create(emitter -> emitter.onSuccess(new Connection()));

          maybe.subscribe(
              conn -> {
                conn.query();
                latch.countDown();
              });

          maybe.subscribe(
              conn -> {
                conn.query();
                latch.countDown();
              });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void observableOnErrorPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Observable.error(new RuntimeException("boom"))
              .subscribe(
                  item -> {},
                  error -> {
                    Connection.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void flowableOnErrorPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Flowable.error(new RuntimeException("boom"))
              .subscribe(
                  item -> {},
                  error -> {
                    Connection.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void singleOnErrorPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Single.error(new RuntimeException("boom"))
              .subscribe(
                  item -> {},
                  error -> {
                    Connection.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void maybeOnErrorPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Maybe.error(new RuntimeException("boom"))
              .subscribe(
                  item -> {},
                  error -> {
                    Connection.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  @Test
  void completableOnErrorPropagatesContext() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    runUnderTrace(
        "parent",
        () -> {
          Completable.error(new RuntimeException("boom"))
              .subscribe(
                  () -> {},
                  error -> {
                    Connection.query();
                    latch.countDown();
                  });
          return null;
        });
    latch.await(5, TimeUnit.SECONDS);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("parent")
                .resourceName("parent")
                .root()
                .tags(defaultTags()),
            SpanMatcher.span()
                .operationName("Connection.query")
                .resourceName("Connection.query")
                .childOfIndex(0)));
  }

  /** Simple helper that creates a traced span when its {@code query()} method is called. */
  static class Connection {
    static int query() {
      AgentSpan span = AgentTracer.startSpan("test", "Connection.query");
      span.finish();
      return new Random().nextInt();
    }
  }
}
