package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import testdog.trace.instrumentation.rxjava3.TracedMethods;

/**
 * Context-propagation tests for RxJava 3.
 *
 * <p>RxJava 3 instrumentation creates NO spans of its own — it only bridges trace context across
 * async boundaries. These tests verify that a span started inside a wrapped callback becomes a
 * child of the parent span that was active when the boundary was created.
 */
public class RxJava3Test extends AbstractInstrumentationTest {

  static {
    // TODO fix this by making sure that spans get closed properly
    // Delayed reactive operators may finish child spans after the root span completes, same as
    // RxJava2Test.useStrictTraceWrites() returning false
    testConfig.strictTraceWrites(false);
  }

  @org.junit.jupiter.api.BeforeAll
  static void warmUpSchedulers() {
    // Warm up RxJava's computation scheduler so the first test using delay() doesn't timeout
    // due to scheduler thread pool initialization interfering with trace context propagation.
    Maybe.just(0).delay(1, MILLISECONDS).blockingGet();
  }

  private static final String EXCEPTION_MESSAGE = "test exception";

  static Function<Integer, Integer> addOne() {
    return i -> TracedMethods.addOneFunc(i);
  }

  static Function<Integer, Integer> addTwo() {
    return i -> TracedMethods.addTwoFunc(i);
  }

  /**
   * Wraps a publisher supplier call under a trace-parent / publisher-parent span pair and
   * subscribes synchronously (blocking).
   *
   * @param publisherSupplier a supplier that creates the reactive publisher
   * @return the result of blocking on the publisher
   */
  Object assemblePublisherUnderTrace(PublisherSupplier publisherSupplier) {
    AgentSpan traceParent = startSpan("trace", "trace-parent");
    traceParent.setResourceName("trace-parent");
    traceParent.setTag(Tags.COMPONENT, "trace");
    AgentScope traceParentScope = activateSpan(traceParent);
    try {
      AgentSpan span = startSpan("test", "publisher-parent");
      AgentScope scope = activateSpan(span);
      try {
        Object publisher = publisherSupplier.get();
        if (publisher instanceof Maybe) {
          return ((Maybe<?>) publisher).blockingGet();
        } else if (publisher instanceof Flowable) {
          return ((Flowable<?>) publisher).toList().blockingGet();
        } else if (publisher instanceof Single) {
          return ((Single<?>) publisher).blockingGet();
        } else if (publisher instanceof Observable) {
          return ((Observable<?>) publisher).toList().blockingGet();
        } else if (publisher instanceof Completable) {
          ((Completable) publisher).blockingAwait();
          return null;
        }
        throw new RuntimeException("Unknown publisher: " + publisher);
      } finally {
        span.finish();
        scope.close();
      }
    } catch (RuntimeException e) {
      traceParent.setError(true);
      traceParent.addThrowable(e);
      throw e;
    } finally {
      traceParent.finish();
      traceParentScope.close();
    }
  }

  /**
   * Creates a publisher and immediately cancels it under a trace-parent / publisher-parent pair.
   *
   * @param publisherSupplier a supplier that creates the reactive publisher
   */
  void cancelUnderTrace(PublisherSupplier publisherSupplier) {
    AgentSpan traceParent = startSpan("trace", "trace-parent");
    traceParent.setResourceName("trace-parent");
    traceParent.setTag(Tags.COMPONENT, "trace");
    AgentScope traceParentScope = activateSpan(traceParent);
    try {
      AgentSpan span = startSpan("test", "publisher-parent");
      AgentScope scope = activateSpan(span);

      Object publisher = publisherSupplier.get();
      Flowable<?> flowable;
      if (publisher instanceof Maybe) {
        flowable = ((Maybe<?>) publisher).toFlowable();
      } else if (publisher instanceof Flowable) {
        flowable = (Flowable<?>) publisher;
      } else if (publisher instanceof Single) {
        flowable = ((Single<?>) publisher).toFlowable();
      } else if (publisher instanceof Observable) {
        flowable = ((Observable<?>) publisher).toFlowable(BackpressureStrategy.BUFFER);
      } else if (publisher instanceof Completable) {
        flowable = ((Completable) publisher).toFlowable();
      } else {
        throw new RuntimeException("Unknown publisher: " + publisher);
      }

      flowable.subscribe(
          new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription subscription) {
              subscription.cancel();
            }

            @Override
            public void onNext(Object t) {}

            @Override
            public void onError(Throwable error) {}

            @Override
            public void onComplete() {}
          });

      scope.close();
      span.finish();
    } finally {
      traceParent.finish();
      traceParentScope.close();
    }
  }

  // ---- Publisher context propagation tests ----

  static Stream<Arguments> publisherTestProvider() {
    return Stream.of(
        Arguments.of("basic maybe", 2, 1, (PublisherSupplier) () -> Maybe.just(1).map(addOne())),
        Arguments.of(
            "two operations maybe",
            4,
            2,
            (PublisherSupplier) () -> Maybe.just(2).map(addOne()).map(addOne())),
        Arguments.of(
            "delayed maybe",
            4,
            1,
            (PublisherSupplier) () -> Maybe.just(3).delay(100, MILLISECONDS).map(addOne())),
        Arguments.of(
            "delayed twice maybe",
            6,
            2,
            (PublisherSupplier)
                () ->
                    Maybe.just(4)
                        .delay(100, MILLISECONDS)
                        .map(addOne())
                        .delay(100, MILLISECONDS)
                        .map(addOne())),
        Arguments.of(
            "basic flowable",
            Arrays.asList(6, 7),
            2,
            (PublisherSupplier) () -> Flowable.fromIterable(Arrays.asList(5, 6)).map(addOne())),
        Arguments.of(
            "two operations flowable",
            Arrays.asList(8, 9),
            4,
            (PublisherSupplier)
                () -> Flowable.fromIterable(Arrays.asList(6, 7)).map(addOne()).map(addOne())),
        Arguments.of(
            "delayed flowable",
            Arrays.asList(8, 9),
            2,
            (PublisherSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(7, 8))
                        .delay(100, MILLISECONDS)
                        .map(addOne())),
        Arguments.of(
            "delayed twice flowable",
            Arrays.asList(10, 11),
            4,
            (PublisherSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(8, 9))
                        .delay(100, MILLISECONDS)
                        .map(addOne())
                        .delay(100, MILLISECONDS)
                        .map(addOne())),
        Arguments.of(
            "maybe from callable",
            12,
            2,
            (PublisherSupplier)
                () -> Maybe.fromCallable(() -> TracedMethods.addOneFunc(10)).map(addOne())),
        Arguments.of("basic single", 2, 1, (PublisherSupplier) () -> Single.just(1).map(addOne())),
        Arguments.of(
            "two operations single",
            4,
            2,
            (PublisherSupplier) () -> Single.just(2).map(addOne()).map(addOne())),
        Arguments.of(
            "basic observable",
            Arrays.asList(6, 7),
            2,
            (PublisherSupplier) () -> Observable.fromIterable(Arrays.asList(5, 6)).map(addOne())),
        Arguments.of(
            "two operations observable",
            Arrays.asList(8, 9),
            4,
            (PublisherSupplier)
                () -> Observable.fromIterable(Arrays.asList(6, 7)).map(addOne()).map(addOne())),
        Arguments.of(
            "completable",
            null,
            1,
            (PublisherSupplier)
                () -> Completable.fromCallable(() -> TracedMethods.addOneFunc(10))));
  }

  @ParameterizedTest(name = "Publisher {0} test")
  @MethodSource("publisherTestProvider")
  void publisherContextPropagation(
      String name, Object expected, int workSpans, PublisherSupplier publisherSupplier) {
    Object result = assemblePublisherUnderTrace(publisherSupplier);
    assertEquals(expected, result);

    SpanMatcher[] allSpans = new SpanMatcher[workSpans + 2];
    allSpans[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    allSpans[1] =
        span()
            .childOfPrevious()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < workSpans; i++) {
      allSpans[i + 2] =
          span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfSpan(1)
              .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    }

    assertTraces(trace(SORT_BY_START_TIME, allSpans));
  }

  // ---- Publisher error tests ----

  static Stream<Arguments> publisherErrorTestProvider() {
    return Stream.of(
        Arguments.of(
            "maybe",
            (PublisherSupplier) () -> Maybe.error(new RuntimeException(EXCEPTION_MESSAGE))),
        Arguments.of(
            "flowable",
            (PublisherSupplier) () -> Flowable.error(new RuntimeException(EXCEPTION_MESSAGE))),
        Arguments.of(
            "single",
            (PublisherSupplier) () -> Single.error(new RuntimeException(EXCEPTION_MESSAGE))),
        Arguments.of(
            "observable",
            (PublisherSupplier) () -> Observable.error(new RuntimeException(EXCEPTION_MESSAGE))),
        Arguments.of(
            "completable",
            (PublisherSupplier) () -> Completable.error(new RuntimeException(EXCEPTION_MESSAGE))));
  }

  @ParameterizedTest(name = "Publisher error {0} test")
  @MethodSource("publisherErrorTestProvider")
  void publisherErrorContextPropagation(String name, PublisherSupplier publisherSupplier) {
    try {
      assemblePublisherUnderTrace(publisherSupplier);
    } catch (RuntimeException expected) {
      // expected — errors propagate through the reactive chain
    }

    // Context-propagation instrumentation does NOT attach errors at the reactive level,
    // so that other integrations (netty, lettuce) are not impacted.
    // Only the trace-parent span is errored because the exception bubbles up to it.
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .root()
                .operationName("trace-parent")
                .resourceName("trace-parent")
                .error()
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, is("trace")),
                    error(RuntimeException.class, EXCEPTION_MESSAGE)),
            span()
                .childOfPrevious()
                .operationName("publisher-parent")
                .resourceName("publisher-parent")
                .tags(defaultTags())));
  }

  // ---- Publisher step error tests ----

  static Stream<Arguments> publisherStepErrorTestProvider() {
    return Stream.of(
        Arguments.of(
            "basic maybe failure",
            1,
            (PublisherSupplier)
                () ->
                    Maybe.just(1)
                        .map(addOne())
                        .map(
                            i -> {
                              throw new RuntimeException(EXCEPTION_MESSAGE);
                            })),
        Arguments.of(
            "basic flowable failure",
            1,
            (PublisherSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(5, 6))
                        .map(addOne())
                        .map(
                            i -> {
                              throw new RuntimeException(EXCEPTION_MESSAGE);
                            })),
        Arguments.of(
            "basic single failure",
            1,
            (PublisherSupplier)
                () ->
                    Single.just(1)
                        .map(addOne())
                        .map(
                            i -> {
                              throw new RuntimeException(EXCEPTION_MESSAGE);
                            })),
        Arguments.of(
            "basic observable failure",
            1,
            (PublisherSupplier)
                () ->
                    Observable.fromIterable(Arrays.asList(5, 6))
                        .map(addOne())
                        .map(
                            i -> {
                              throw new RuntimeException(EXCEPTION_MESSAGE);
                            })));
  }

  @ParameterizedTest(name = "Publisher step {0} test")
  @MethodSource("publisherStepErrorTestProvider")
  void publisherStepErrorContextPropagation(
      String name, int workSpans, PublisherSupplier publisherSupplier) {
    try {
      assemblePublisherUnderTrace(publisherSupplier);
    } catch (RuntimeException expected) {
      // expected — mid-chain error
    }

    SpanMatcher[] allSpans = new SpanMatcher[workSpans + 2];
    allSpans[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .error()
            .tags(
                defaultTags(),
                tag(Tags.COMPONENT, is("trace")),
                error(RuntimeException.class, EXCEPTION_MESSAGE));
    allSpans[1] =
        span()
            .childOfPrevious()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < workSpans; i++) {
      allSpans[i + 2] =
          span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfSpan(1)
              .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    }

    assertTraces(trace(SORT_BY_START_TIME, allSpans));
  }

  // ---- Cancel tests ----

  static Stream<Arguments> publisherCancelTestProvider() {
    return Stream.of(
        Arguments.of("basic maybe", (PublisherSupplier) () -> Maybe.just(1)),
        Arguments.of(
            "basic flowable", (PublisherSupplier) () -> Flowable.fromIterable(Arrays.asList(5, 6))),
        Arguments.of("basic single", (PublisherSupplier) () -> Single.just(1)),
        Arguments.of(
            "basic observable",
            (PublisherSupplier) () -> Observable.fromIterable(Arrays.asList(5, 6))),
        Arguments.of("basic completable", (PublisherSupplier) () -> Completable.complete()));
  }

  @ParameterizedTest(name = "Publisher {0} cancel")
  @MethodSource("publisherCancelTestProvider")
  void publisherCancelContextPropagation(String name, PublisherSupplier publisherSupplier) {
    cancelUnderTrace(publisherSupplier);

    assertTraces(
        trace(
            span()
                .root()
                .operationName("trace-parent")
                .resourceName("trace-parent")
                .tags(defaultTags(), tag(Tags.COMPONENT, is("trace"))),
            span()
                .childOfPrevious()
                .operationName("publisher-parent")
                .resourceName("publisher-parent")
                .tags(defaultTags())));
  }

  // ---- Chain parent tests ----

  static Stream<Arguments> publisherChainParentTestProvider() {
    return Stream.of(
        Arguments.of(
            "basic maybe",
            3,
            (PublisherSupplier)
                () ->
                    Maybe.just(1)
                        .map(addOne())
                        .map(addOne())
                        .concatWith(Maybe.just(1).map(addOne()))),
        Arguments.of(
            "basic flowable",
            5,
            (PublisherSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(5, 6))
                        .map(addOne())
                        .map(addOne())
                        .concatWith(Maybe.just(1).map(addOne()).toFlowable())));
  }

  @ParameterizedTest(name = "Publisher chain spans have the correct parent for {0}")
  @MethodSource("publisherChainParentTestProvider")
  void publisherChainParentContextPropagation(
      String name, int workSpans, PublisherSupplier publisherSupplier) {
    assemblePublisherUnderTrace(publisherSupplier);

    SpanMatcher[] allSpans = new SpanMatcher[workSpans + 2];
    allSpans[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    allSpans[1] =
        span()
            .childOfPrevious()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < workSpans; i++) {
      allSpans[i + 2] =
          span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfSpan(1)
              .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    }

    assertTraces(trace(SORT_BY_START_TIME, allSpans));
  }

  // ---- Subscription time parent tests ----

  @Test
  void publisherChainSpansHaveCorrectParentsFromSubscriptionTime() {
    Maybe<Integer> maybe = Maybe.just(42).map(addOne()).map(addTwo());

    AgentSpan parent = startSpan("test", "trace-parent");
    AgentScope scope = activateSpan(parent);
    try {
      maybe.blockingGet();
    } finally {
      scope.close();
      parent.finish();
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("trace-parent"),
            span()
                .childOfSpan(0)
                .operationName("addOne")
                .tags(defaultTags(), tag(Tags.COMPONENT, is("trace"))),
            span()
                .childOfSpan(0)
                .operationName("addTwo")
                .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")))));
  }

  // ---- Subscription time parent with intermediate span tests ----

  static Stream<Arguments> publisherSubscriptionTimeParentTestProvider() {
    return Stream.of(
        Arguments.of("basic maybe", 1, (PublisherSupplier) () -> Maybe.just(1).map(addOne())),
        Arguments.of(
            "basic flowable",
            2,
            (PublisherSupplier) () -> Flowable.fromIterable(Arrays.asList(1, 2)).map(addOne())));
  }

  @ParameterizedTest(name = "Publisher chain spans from subscription time {0}")
  @MethodSource("publisherSubscriptionTimeParentTestProvider")
  void publisherSubscriptionTimeContextPropagation(
      String name, int workItems, PublisherSupplier publisherSupplier) {
    assemblePublisherUnderTrace(
        () -> {
          Object publisher = publisherSupplier.get();

          AgentSpan intermediate = startSpan("test", "intermediate");
          AgentScope intermediateScope = activateSpan(intermediate);
          try {
            if (publisher instanceof Maybe) {
              @SuppressWarnings("unchecked")
              Maybe<Integer> maybe = (Maybe<Integer>) publisher;
              return maybe.map(addTwo());
            } else if (publisher instanceof Flowable) {
              @SuppressWarnings("unchecked")
              Flowable<Integer> flowable = (Flowable<Integer>) publisher;
              return flowable.map(addTwo());
            }
            throw new IllegalStateException("Unknown publisher type");
          } finally {
            intermediate.finish();
            intermediateScope.close();
          }
        });

    int totalSpans = 3 + 2 * workItems;
    SpanMatcher[] allSpans = new SpanMatcher[totalSpans];
    allSpans[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    allSpans[1] =
        span()
            .childOfPrevious()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    allSpans[2] = span().childOfPrevious().operationName("intermediate").tags(defaultTags());

    for (int i = 0; i < 2 * workItems; i += 2) {
      allSpans[3 + i] =
          span()
              .operationName("addOne")
              .childOfSpan(1)
              .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
      allSpans[4 + i] =
          span()
              .operationName("addTwo")
              .childOfSpan(1)
              .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    }

    assertTraces(trace(SORT_BY_START_TIME, allSpans));
  }

  // ---- Flowable parallel scheduler test ----

  static Stream<Arguments> schedulerTestProvider() {
    return Stream.of(
        Arguments.of("new-thread", Schedulers.newThread()),
        Arguments.of("computation", Schedulers.computation()),
        Arguments.of("single", Schedulers.single()),
        Arguments.of("trampoline", Schedulers.trampoline()));
  }

  @ParameterizedTest(name = "Flowables propagate context on {0} scheduler")
  @MethodSource("schedulerTestProvider")
  void flowableParallelOnScheduler(
      String schedulerName, io.reactivex.rxjava3.core.Scheduler scheduler) {
    Object result =
        assemblePublisherUnderTrace(
            () ->
                Flowable.fromIterable(Arrays.asList(1, 2, 3, 4))
                    .parallel()
                    .runOn(scheduler)
                    .flatMap(num -> Maybe.just(num).map(addOne()).toFlowable())
                    .sequential());

    List<?> values = (List<?>) result;
    assertEquals(4, values.size());

    // 2 parent spans + 4 addOne work spans
    SpanMatcher[] allSpans = new SpanMatcher[6];
    allSpans[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    allSpans[1] =
        span()
            .childOfPrevious()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < 4; i++) {
      allSpans[i + 2] =
          span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfSpan(1)
              .tags(defaultTags(), tag(Tags.COMPONENT, is("trace")));
    }

    assertTraces(trace(SORT_BY_START_TIME, allSpans));
  }

  /** Functional interface for publisher suppliers (avoids Groovy closures). */
  @FunctionalInterface
  interface PublisherSupplier {
    Object get();
  }
}
