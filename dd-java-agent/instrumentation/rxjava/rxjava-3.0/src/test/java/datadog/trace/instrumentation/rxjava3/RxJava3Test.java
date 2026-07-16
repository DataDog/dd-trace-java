package datadog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.Matchers;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Context-propagation tests for RxJava 3.
 *
 * <p>RxJava instrumentation does NOT create spans itself — it propagates the active trace context
 * across async boundaries so that spans started inside reactive callbacks become children of the
 * span that was active when the reactive chain was subscribed to.
 */
class RxJava3Test extends AbstractInstrumentationTest {

  // ---------------------------------------------------------------------------
  // Helper traced methods — each creates a child span under the current context
  // ---------------------------------------------------------------------------

  static int addOneFunc(int i) {
    AgentSpan span = startSpan("test", "addOne");
    span.setResourceName("addOne");
    span.setTag("component", "trace");
    AgentScope scope = activateSpan(span);
    try {
      return i + 1;
    } finally {
      scope.close();
      span.finish();
    }
  }

  static int addTwoFunc(int i) {
    AgentSpan span = startSpan("test", "addTwo");
    span.setResourceName("addTwo");
    span.setTag("component", "trace");
    AgentScope scope = activateSpan(span);
    try {
      return i + 2;
    } finally {
      scope.close();
      span.finish();
    }
  }

  private static final Function<Integer, Integer> ADD_ONE = RxJava3Test::addOneFunc;
  private static final Function<Integer, Integer> ADD_TWO = RxJava3Test::addTwoFunc;

  // ---------------------------------------------------------------------------
  // Publisher test: verify child spans are parented under the trace-parent
  // ---------------------------------------------------------------------------

  static Stream<Arguments> publisherTestCases() {
    return Stream.of(
        Arguments.of(
            "basic maybe", 2, 1, (CallableSupplier) () -> Maybe.just(1).map(ADD_ONE::apply)),
        Arguments.of(
            "two operations maybe",
            4,
            2,
            (CallableSupplier) () -> Maybe.just(2).map(ADD_ONE::apply).map(ADD_ONE::apply)),
        Arguments.of(
            "delayed maybe",
            4,
            1,
            (CallableSupplier) () -> Maybe.just(3).delay(100, MILLISECONDS).map(ADD_ONE::apply)),
        Arguments.of(
            "delayed twice maybe",
            6,
            2,
            (CallableSupplier)
                () ->
                    Maybe.just(4)
                        .delay(100, MILLISECONDS)
                        .map(ADD_ONE::apply)
                        .delay(100, MILLISECONDS)
                        .map(ADD_ONE::apply)),
        Arguments.of(
            "basic flowable",
            null,
            2,
            (CallableSupplier)
                () -> Flowable.fromIterable(Arrays.asList(5, 6)).map(ADD_ONE::apply)),
        Arguments.of(
            "two operations flowable",
            null,
            4,
            (CallableSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(6, 7))
                        .map(ADD_ONE::apply)
                        .map(ADD_ONE::apply)),
        Arguments.of(
            "delayed flowable",
            null,
            2,
            (CallableSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(7, 8))
                        .delay(100, MILLISECONDS)
                        .map(ADD_ONE::apply)),
        Arguments.of(
            "delayed twice flowable",
            null,
            4,
            (CallableSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(8, 9))
                        .delay(100, MILLISECONDS)
                        .map(ADD_ONE::apply)
                        .delay(100, MILLISECONDS)
                        .map(ADD_ONE::apply)),
        Arguments.of(
            "maybe from callable",
            12,
            2,
            (CallableSupplier) () -> Maybe.fromCallable(() -> addOneFunc(10)).map(ADD_ONE::apply)));
  }

  @ParameterizedTest(name = "Publisher ''{0}'' test")
  @MethodSource("publisherTestCases")
  void publisherTest(
      String name, Object expected, int workSpans, CallableSupplier publisherSupplier)
      throws Exception {
    Object result = assemblePublisherUnderTrace(publisherSupplier);

    SpanMatcher[] spanMatchers = new SpanMatcher[workSpans + 2];
    spanMatchers[0] =
        SpanMatcher.span().operationName("trace-parent").resourceName("trace-parent").root();
    spanMatchers[1] =
        SpanMatcher.span()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .childOfIndex(0);
    for (int i = 0; i < workSpans; i++) {
      spanMatchers[i + 2] =
          SpanMatcher.span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfIndex(1)
              .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace")));
    }
    assertTraces(trace(SORT_BY_START_TIME, spanMatchers));
  }

  // ---------------------------------------------------------------------------
  // Publisher error: errors propagate up but do NOT set error on the reactive span
  // ---------------------------------------------------------------------------

  static Stream<Arguments> publisherErrorTestCases() {
    return Stream.of(
        Arguments.of(
            "maybe", (CallableSupplier) () -> Maybe.error(new RuntimeException("test exception"))),
        Arguments.of(
            "flowable",
            (CallableSupplier) () -> Flowable.error(new RuntimeException("test exception"))));
  }

  @ParameterizedTest(name = "Publisher error ''{0}'' test")
  @MethodSource("publisherErrorTestCases")
  void publisherErrorTest(String name, CallableSupplier publisherSupplier) {
    assertThrows(RuntimeException.class, () -> assemblePublisherUnderTrace(publisherSupplier));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span()
                .operationName("trace-parent")
                .resourceName("trace-parent")
                .root()
                .error()
                .tags(defaultTags(), TagsMatcher.error(RuntimeException.class, "test exception")),
            SpanMatcher.span()
                .operationName("publisher-parent")
                .resourceName("publisher-parent")
                .childOfIndex(0)));
  }

  // ---------------------------------------------------------------------------
  // Publisher step error: error in map step after a successful step
  // ---------------------------------------------------------------------------

  static Stream<Arguments> publisherStepErrorTestCases() {
    return Stream.of(
        Arguments.of(
            "basic maybe failure",
            1,
            (CallableSupplier)
                () ->
                    Maybe.just(1)
                        .map(ADD_ONE::apply)
                        .map(
                            i -> {
                              throw new RuntimeException("test exception");
                            })),
        Arguments.of(
            "basic flowable failure",
            1,
            (CallableSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(5, 6))
                        .map(ADD_ONE::apply)
                        .map(
                            i -> {
                              throw new RuntimeException("test exception");
                            })));
  }

  @ParameterizedTest(name = "Publisher step ''{0}'' test")
  @MethodSource("publisherStepErrorTestCases")
  void publisherStepErrorTest(String name, int workSpans, CallableSupplier publisherSupplier) {
    assertThrows(RuntimeException.class, () -> assemblePublisherUnderTrace(publisherSupplier));

    SpanMatcher[] spanMatchers = new SpanMatcher[workSpans + 2];
    spanMatchers[0] =
        SpanMatcher.span()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .root()
            .error()
            .tags(defaultTags(), TagsMatcher.error(RuntimeException.class, "test exception"));
    spanMatchers[1] =
        SpanMatcher.span()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .childOfIndex(0);
    for (int i = 0; i < workSpans; i++) {
      spanMatchers[i + 2] =
          SpanMatcher.span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfIndex(1)
              .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace")));
    }
    assertTraces(trace(SORT_BY_START_TIME, spanMatchers));
  }

  // ---------------------------------------------------------------------------
  // Publisher cancel: cancelled subscription still produces correct trace
  // ---------------------------------------------------------------------------

  static Stream<Arguments> publisherCancelTestCases() {
    return Stream.of(
        Arguments.of("basic maybe", (CallableSupplier) () -> Maybe.just(1)),
        Arguments.of(
            "basic flowable", (CallableSupplier) () -> Flowable.fromIterable(Arrays.asList(5, 6))));
  }

  @ParameterizedTest(name = "Publisher ''{0}'' cancel")
  @MethodSource("publisherCancelTestCases")
  void publisherCancelTest(String name, CallableSupplier publisherSupplier) throws Exception {
    cancelUnderTrace(publisherSupplier);

    assertTraces(
        trace(
            SpanMatcher.span().operationName("trace-parent").resourceName("trace-parent").root(),
            SpanMatcher.span()
                .operationName("publisher-parent")
                .resourceName("publisher-parent")
                .childOfIndex(0)));
  }

  // ---------------------------------------------------------------------------
  // Chain spans: correct parent across concat operations
  // ---------------------------------------------------------------------------

  static Stream<Arguments> chainSpansTestCases() {
    return Stream.of(
        Arguments.of(
            "basic maybe",
            3,
            (CallableSupplier)
                () ->
                    Maybe.just(1)
                        .map(ADD_ONE::apply)
                        .map(ADD_ONE::apply)
                        .concatWith(Maybe.just(1).map(ADD_ONE::apply))),
        Arguments.of(
            "basic flowable",
            5,
            (CallableSupplier)
                () ->
                    Flowable.fromIterable(Arrays.asList(5, 6))
                        .map(ADD_ONE::apply)
                        .map(ADD_ONE::apply)
                        .concatWith(Maybe.just(1).map(ADD_ONE::apply).toFlowable())));
  }

  @ParameterizedTest(name = "Publisher chain spans have the correct parent for ''{0}''")
  @MethodSource("chainSpansTestCases")
  void chainSpansTest(String name, int workSpans, CallableSupplier publisherSupplier)
      throws Exception {
    assemblePublisherUnderTrace(publisherSupplier);

    SpanMatcher[] spanMatchers = new SpanMatcher[workSpans + 2];
    spanMatchers[0] =
        SpanMatcher.span().operationName("trace-parent").resourceName("trace-parent").root();
    spanMatchers[1] =
        SpanMatcher.span()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .childOfIndex(0);
    for (int i = 0; i < workSpans; i++) {
      spanMatchers[i + 2] =
          SpanMatcher.span()
              .operationName("addOne")
              .resourceName("addOne")
              .childOfIndex(1)
              .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace")));
    }
    assertTraces(trace(SORT_BY_START_TIME, spanMatchers));
  }

  // ---------------------------------------------------------------------------
  // Subscription-time parent: spans inherit the parent active at subscribe time
  // ---------------------------------------------------------------------------

  @Test
  void publisherChainSpansHaveCorrectParentFromSubscriptionTime() throws Exception {
    Maybe<Integer> maybe = Maybe.just(42).map(ADD_ONE::apply).map(ADD_TWO::apply);

    runUnderTrace(
        "trace-parent",
        () -> {
          maybe.blockingGet();
          return null;
        });

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            SpanMatcher.span().operationName("trace-parent").resourceName("trace-parent").root(),
            SpanMatcher.span()
                .operationName("addOne")
                .childOfIndex(0)
                .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace"))),
            SpanMatcher.span()
                .operationName("addTwo")
                .childOfIndex(0)
                .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace")))));
  }

  // ---------------------------------------------------------------------------
  // Intermediate span: spans get the right parent when an intermediate scope
  // is active between publisher creation and subscribe
  // ---------------------------------------------------------------------------

  static Stream<Arguments> intermediateSpanTestCases() {
    return Stream.of(
        Arguments.of("basic maybe", 1, (CallableSupplier) () -> Maybe.just(1).map(ADD_ONE::apply)),
        Arguments.of(
            "basic flowable",
            2,
            (CallableSupplier)
                () -> Flowable.fromIterable(Arrays.asList(1, 2)).map(ADD_ONE::apply)));
  }

  @ParameterizedTest(
      name = "Publisher chain spans have the correct parents from subscription time ''{0}''")
  @MethodSource("intermediateSpanTestCases")
  void intermediateSpanTest(String name, int workItems, CallableSupplier publisherSupplier)
      throws Exception {
    assemblePublisherWithIntermediateSpan(publisherSupplier, workItems);

    int totalSpans = 3 + 2 * workItems;
    SpanMatcher[] spanMatchers = new SpanMatcher[totalSpans];
    spanMatchers[0] =
        SpanMatcher.span().operationName("trace-parent").resourceName("trace-parent").root();
    spanMatchers[1] =
        SpanMatcher.span()
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .childOfIndex(0);
    spanMatchers[2] = SpanMatcher.span().operationName("intermediate").childOfIndex(1);

    for (int i = 0; i < 2 * workItems; i += 2) {
      spanMatchers[3 + i] =
          SpanMatcher.span()
              .operationName("addOne")
              .childOfIndex(1)
              .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace")));
      spanMatchers[3 + i + 1] =
          SpanMatcher.span()
              .operationName("addTwo")
              .childOfIndex(1)
              .tags(defaultTags(), TagsMatcher.tag("component", Matchers.is("trace")));
    }
    assertTraces(trace(SORT_BY_START_TIME, spanMatchers));
  }

  // ---------------------------------------------------------------------------
  // Scheduler test: flowable produces correct number of results on each scheduler
  // ---------------------------------------------------------------------------

  static Stream<Arguments> schedulerTestCases() {
    return Stream.of(
        Arguments.of("new-thread", Schedulers.newThread()),
        Arguments.of("computation", Schedulers.computation()),
        Arguments.of("single", Schedulers.single()),
        Arguments.of("trampoline", Schedulers.trampoline()));
  }

  @ParameterizedTest(name = "Flowables produce the right number of results on ''{0}'' scheduler")
  @MethodSource("schedulerTestCases")
  void schedulerTest(String schedulerName, io.reactivex.rxjava3.core.Scheduler scheduler) {
    List<String> values =
        Flowable.fromIterable(Arrays.asList(1, 2, 3, 4))
            .parallel()
            .runOn(scheduler)
            .flatMap(
                num ->
                    Maybe.just(num.toString() + " on " + Thread.currentThread().getName())
                        .toFlowable())
            .sequential()
            .toList()
            .blockingGet();

    assertEquals(4, values.size());
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------

  private Object assemblePublisherUnderTrace(CallableSupplier publisherSupplier) throws Exception {
    return runUnderTrace(
        "trace-parent",
        () -> {
          AgentSpan span = startSpan("test", "publisher-parent");
          AgentScope scope = activateSpan(span);

          Object publisher = publisherSupplier.call();
          try {
            if (publisher instanceof Maybe) {
              return ((Maybe<?>) publisher).blockingGet();
            } else if (publisher instanceof Flowable) {
              return ((Flowable<?>) publisher).toList().blockingGet().toArray(new Integer[0]);
            }
            throw new RuntimeException("Unknown publisher: " + publisher);
          } finally {
            span.finish();
            scope.close();
          }
        });
  }

  private void cancelUnderTrace(CallableSupplier publisherSupplier) throws Exception {
    runUnderTrace(
        "trace-parent",
        () -> {
          AgentSpan span = startSpan("test", "publisher-parent");
          AgentScope scope = activateSpan(span);

          Object publisher = publisherSupplier.call();
          if (publisher instanceof Maybe) {
            publisher = ((Maybe<?>) publisher).toFlowable();
          }

          ((Flowable<?>) publisher)
              .subscribe(
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
          return null;
        });
  }

  @SuppressWarnings("unchecked")
  private void assemblePublisherWithIntermediateSpan(
      CallableSupplier publisherSupplier, int workItems) throws Exception {
    runUnderTrace(
        "trace-parent",
        () -> {
          AgentSpan parentSpan = startSpan("test", "publisher-parent");
          AgentScope parentScope = activateSpan(parentSpan);

          Object publisher = publisherSupplier.call();

          AgentSpan intermediate = startSpan("test", "intermediate");
          AgentScope intermediateScope = activateSpan(intermediate);
          try {
            if (publisher instanceof Maybe) {
              ((Maybe<Integer>) publisher).map(ADD_TWO::apply).blockingGet();
            } else if (publisher instanceof Flowable) {
              ((Flowable<Integer>) publisher).map(ADD_TWO::apply).toList().blockingGet();
            } else {
              throw new IllegalStateException("Unknown publisher type");
            }
          } finally {
            intermediate.finish();
            intermediateScope.close();
          }

          parentSpan.finish();
          parentScope.close();
          return null;
        });
  }

  /** Functional interface that can throw checked exceptions. */
  @FunctionalInterface
  interface CallableSupplier {
    Object call() throws Exception;
  }
}
