package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

// NOTE: This test lives in the `testdog` package (not `datadog`) on purpose: the agent ignores
// `datadog.*` classes for instrumentation, so `@Trace`-annotated methods declared under `datadog.*`
// would never be instrumented. See the java-lang-21 tests for the same convention.
class RxJava3Test extends AbstractInstrumentationTest {

  static {
    // The reactive chains in these scenarios can finish child spans after the local root has been
    // written (e.g. delayed/scheduled work), which trips strict trace write ordering checks. This
    // mirrors the Groovy RxJava2Test which also disables strict trace writes for the same reason.
    testConfig.strictTraceWrites(false);
  }

  static final String EXCEPTION_MESSAGE = "test exception";

  // The component tag is stored as a UTF8BytesString, so we compare by string content rather than
  // using is("trace") which would fail the asymmetric String#equals(UTF8BytesString) check.
  static TagsMatcher componentTrace() {
    return tag(Tags.COMPONENT, validates(o -> "trace".equals(String.valueOf(o))));
  }

  /**
   * Holds the {@code @Trace}-annotated methods used by the scenarios. The captured span ids are
   * stored in static fields and read back by the asserting test methods to express cross-span
   * parent relationships.
   */
  static class Worker {
    static long traceParentId;
    static long publisherParentId;
    static long intermediateId;

    static int addOne(int i) {
      return addOneTraced(i);
    }

    @Trace(operationName = "addOne", resourceName = "addOne")
    static int addOneTraced(int i) {
      return i + 1;
    }

    static int addTwo(int i) {
      return addTwoTraced(i);
    }

    @Trace(operationName = "addTwo", resourceName = "addTwo")
    static int addTwoTraced(int i) {
      return i + 2;
    }

    static Object throwException() {
      throw new RuntimeException(EXCEPTION_MESSAGE);
    }

    @Trace(operationName = "trace-parent", resourceName = "trace-parent")
    @SuppressWarnings("unchecked")
    static Object assemblePublisherUnderTrace(Supplier<Object> publisherSupplier) {
      traceParentId = activeSpan().getSpanId();
      AgentSpan span = startSpan("test", "publisher-parent");
      publisherParentId = span.getSpanId();
      // After this activation, the operations below should be children of this span
      AgentScope scope = activateSpan(span);

      Object publisher = publisherSupplier.get();
      try {
        // Read all data from publisher
        if (publisher instanceof Maybe) {
          return ((Maybe<Object>) publisher).blockingGet();
        } else if (publisher instanceof Flowable) {
          List<Object> list = ((Flowable<Object>) publisher).toList().blockingGet();
          return list.toArray(new Object[0]);
        }
        throw new RuntimeException("Unknown publisher: " + publisher);
      } finally {
        span.finish();
        scope.close();
      }
    }

    @Trace(operationName = "trace-parent", resourceName = "trace-parent")
    static void cancelUnderTrace(Supplier<Object> publisherSupplier) {
      traceParentId = activeSpan().getSpanId();
      AgentSpan span = startSpan("test", "publisher-parent");
      publisherParentId = span.getSpanId();
      AgentScope scope = activateSpan(span);

      Object publisher = publisherSupplier.get();
      Flowable<?> flowable;
      if (publisher instanceof Maybe) {
        flowable = ((Maybe<?>) publisher).toFlowable();
      } else {
        flowable = (Flowable<?>) publisher;
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
    }

    @Trace(operationName = "trace-parent", resourceName = "trace-parent")
    static Object runUnderTraceParent(Supplier<Object> work) {
      traceParentId = activeSpan().getSpanId();
      return work.get();
    }
  }

  // --- Publisher success ---------------------------------------------------

  static List<Arguments> publisherSuccessArgs() {
    return Arrays.asList(
        Arguments.of(
            "basic maybe",
            new Object[] {2},
            1,
            (Supplier<Object>) () -> Maybe.just(1).map(Worker::addOne)),
        Arguments.of(
            "two operations maybe",
            new Object[] {4},
            2,
            (Supplier<Object>) () -> Maybe.just(2).map(Worker::addOne).map(Worker::addOne)),
        Arguments.of(
            "delayed maybe",
            new Object[] {4},
            1,
            (Supplier<Object>)
                () -> Maybe.just(3).delay(100, MILLISECONDS).map(Worker::addOne)),
        Arguments.of(
            "delayed twice maybe",
            new Object[] {6},
            2,
            (Supplier<Object>)
                () ->
                    Maybe.just(4)
                        .delay(100, MILLISECONDS)
                        .map(Worker::addOne)
                        .delay(100, MILLISECONDS)
                        .map(Worker::addOne)),
        Arguments.of(
            "basic flowable",
            new Object[] {6, 7},
            2,
            (Supplier<Object>)
                () -> Flowable.fromIterable(Arrays.asList(5, 6)).map(Worker::addOne)),
        Arguments.of(
            "two operations flowable",
            new Object[] {8, 9},
            4,
            (Supplier<Object>)
                () ->
                    Flowable.fromIterable(Arrays.asList(6, 7))
                        .map(Worker::addOne)
                        .map(Worker::addOne)),
        Arguments.of(
            "delayed flowable",
            new Object[] {8, 9},
            2,
            (Supplier<Object>)
                () ->
                    Flowable.fromIterable(Arrays.asList(7, 8))
                        .delay(100, MILLISECONDS)
                        .map(Worker::addOne)),
        Arguments.of(
            "delayed twice flowable",
            new Object[] {10, 11},
            4,
            (Supplier<Object>)
                () ->
                    Flowable.fromIterable(Arrays.asList(8, 9))
                        .delay(100, MILLISECONDS)
                        .map(Worker::addOne)
                        .delay(100, MILLISECONDS)
                        .map(Worker::addOne)),
        Arguments.of(
            "maybe from callable",
            new Object[] {12},
            2,
            (Supplier<Object>)
                () -> Maybe.fromCallable(() -> Worker.addOne(10)).map(Worker::addOne)));
  }

  @ParameterizedTest(name = "Publisher ''{0}'' test")
  @MethodSource("publisherSuccessArgs")
  void publisherSuccess(String name, Object[] expected, int workSpans, Supplier<Object> supplier) {
    Object result = Worker.assemblePublisherUnderTrace(supplier);

    if (expected.length == 1) {
      assertEquals(expected[0], result);
    } else {
      assertArrayEquals(expected, (Object[]) result);
    }

    SpanMatcher[] matchers = new SpanMatcher[workSpans + 2];
    matchers[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(componentTrace(), defaultTags());
    matchers[1] =
        span()
            .id(Worker.publisherParentId)
            .childOf(Worker.traceParentId)
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < workSpans; i++) {
      matchers[2 + i] =
          span()
              .childOf(Worker.publisherParentId)
              .operationName("addOne")
              .resourceName("addOne")
              .tags(componentTrace(), defaultTags());
    }

    assertTraces(trace(SORT_BY_START_TIME, matchers));
  }

  // --- Publisher error -----------------------------------------------------

  static List<Arguments> publisherErrorArgs() {
    return Arrays.asList(
        Arguments.of(
            "maybe", (Supplier<Object>) () -> Maybe.error(new RuntimeException(EXCEPTION_MESSAGE))),
        Arguments.of(
            "flowable",
            (Supplier<Object>) () -> Flowable.error(new RuntimeException(EXCEPTION_MESSAGE))));
  }

  @ParameterizedTest(name = "Publisher error ''{0}'' test")
  @MethodSource("publisherErrorArgs")
  void publisherError(String name, Supplier<Object> supplier) {
    RuntimeException exception =
        assertThrows(RuntimeException.class, () -> Worker.assemblePublisherUnderTrace(supplier));
    assertEquals(EXCEPTION_MESSAGE, exception.getMessage());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .root()
                .operationName("trace-parent")
                .resourceName("trace-parent")
                .error()
                .tags(
                    componentTrace(),
                    error(RuntimeException.class, EXCEPTION_MESSAGE),
                    defaultTags()),
            // It's important that we don't attach errors at the reactive level so that we don't
            // impact the spans on reactive integrations such as netty and lettuce.
            span()
                .id(Worker.publisherParentId)
                .childOf(Worker.traceParentId)
                .operationName("publisher-parent")
                .resourceName("publisher-parent")
                .tags(defaultTags())));
  }

  // --- Publisher step error ------------------------------------------------

  static List<Arguments> publisherStepErrorArgs() {
    return Arrays.asList(
        Arguments.of(
            "basic maybe failure",
            1,
            (Supplier<Object>)
                () -> Maybe.just(1).map(Worker::addOne).map(i -> Worker.throwException())),
        Arguments.of(
            "basic flowable failure",
            1,
            (Supplier<Object>)
                () ->
                    Flowable.fromIterable(Arrays.asList(5, 6))
                        .map(Worker::addOne)
                        .map(i -> Worker.throwException())));
  }

  @ParameterizedTest(name = "Publisher step ''{0}'' test")
  @MethodSource("publisherStepErrorArgs")
  void publisherStepError(String name, int workSpans, Supplier<Object> supplier) {
    RuntimeException exception =
        assertThrows(RuntimeException.class, () -> Worker.assemblePublisherUnderTrace(supplier));
    assertEquals(EXCEPTION_MESSAGE, exception.getMessage());

    SpanMatcher[] matchers = new SpanMatcher[workSpans + 2];
    matchers[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .error()
            .tags(
                componentTrace(), error(RuntimeException.class, EXCEPTION_MESSAGE), defaultTags());
    matchers[1] =
        span()
            .id(Worker.publisherParentId)
            .childOf(Worker.traceParentId)
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < workSpans; i++) {
      matchers[2 + i] =
          span()
              .childOf(Worker.publisherParentId)
              .operationName("addOne")
              .resourceName("addOne")
              .tags(componentTrace(), defaultTags());
    }

    assertTraces(trace(SORT_BY_START_TIME, matchers));
  }

  // --- Cancel --------------------------------------------------------------

  static List<Arguments> cancelArgs() {
    return Arrays.asList(
        Arguments.of("basic maybe", (Supplier<Object>) () -> Maybe.just(1)),
        Arguments.of(
            "basic flowable", (Supplier<Object>) () -> Flowable.fromIterable(Arrays.asList(5, 6))));
  }

  @ParameterizedTest(name = "Publisher ''{0}'' cancel")
  @MethodSource("cancelArgs")
  void cancel(String name, Supplier<Object> supplier) {
    Worker.cancelUnderTrace(supplier);

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .root()
                .operationName("trace-parent")
                .resourceName("trace-parent")
                .tags(componentTrace(), defaultTags()),
            span()
                .id(Worker.publisherParentId)
                .childOf(Worker.traceParentId)
                .operationName("publisher-parent")
                .resourceName("publisher-parent")
                .tags(defaultTags())));
  }

  // --- Chain spans correct parent ------------------------------------------

  static List<Arguments> chainParentArgs() {
    return Arrays.asList(
        Arguments.of(
            "basic maybe",
            3,
            (Supplier<Object>)
                () ->
                    Maybe.just(1)
                        .map(Worker::addOne)
                        .map(Worker::addOne)
                        .concatWith(Maybe.just(1).map(Worker::addOne))),
        Arguments.of(
            "basic flowable",
            5,
            (Supplier<Object>)
                () ->
                    Flowable.fromIterable(Arrays.asList(5, 6))
                        .map(Worker::addOne)
                        .map(Worker::addOne)
                        .concatWith(Maybe.just(1).map(Worker::addOne).toFlowable())));
  }

  @ParameterizedTest(name = "Publisher chain spans have the correct parent for ''{0}''")
  @MethodSource("chainParentArgs")
  void chainParent(String name, int workSpans, Supplier<Object> supplier) {
    Worker.assemblePublisherUnderTrace(supplier);

    SpanMatcher[] matchers = new SpanMatcher[workSpans + 2];
    matchers[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(componentTrace(), defaultTags());
    matchers[1] =
        span()
            .id(Worker.publisherParentId)
            .childOf(Worker.traceParentId)
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    for (int i = 0; i < workSpans; i++) {
      matchers[2 + i] =
          span()
              .childOf(Worker.publisherParentId)
              .operationName("addOne")
              .resourceName("addOne")
              .tags(componentTrace(), defaultTags());
    }

    assertTraces(trace(SORT_BY_START_TIME, matchers));
  }

  // --- Correct parents from subscription time (blockingGet) ----------------

  @Test
  void correctParentsFromSubscriptionTimeBlockingGet() {
    Maybe<Integer> maybe = Maybe.just(42).map(Worker::addOne).map(Worker::addTwo);

    Worker.runUnderTraceParent(
        () -> {
          maybe.blockingGet();
          return null;
        });

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("trace-parent").resourceName("trace-parent"),
            span()
                .childOf(Worker.traceParentId)
                .operationName("addOne")
                .tags(componentTrace(), defaultTags()),
            span()
                .childOf(Worker.traceParentId)
                .operationName("addTwo")
                .tags(componentTrace(), defaultTags())));
  }

  // --- Correct parents from subscription time (intermediate span) ----------

  static List<Arguments> subscriptionTimeIntermediateArgs() {
    return Arrays.asList(
        Arguments.of("basic maybe", 1, (Supplier<Object>) () -> Maybe.just(1).map(Worker::addOne)),
        Arguments.of(
            "basic flowable",
            2,
            (Supplier<Object>)
                () -> Flowable.fromIterable(Arrays.asList(1, 2)).map(Worker::addOne)));
  }

  @ParameterizedTest(
      name = "Publisher chain spans have the correct parents from subscription time ''{0}''")
  @MethodSource("subscriptionTimeIntermediateArgs")
  @SuppressWarnings("unchecked")
  void correctParentsFromSubscriptionTime(String name, int workItems, Supplier<Object> supplier) {
    Worker.assemblePublisherUnderTrace(
        () -> {
          // The "add one" operations in the publisher created here should be children of the
          // publisher-parent, while the "add two" operations should be children of the
          // intermediate.
          Object publisher = supplier.get();

          AgentSpan intermediate = startSpan("test", "intermediate");
          Worker.intermediateId = intermediate.getSpanId();
          AgentScope scope = activateSpan(intermediate);
          try {
            if (publisher instanceof Maybe) {
              return ((Maybe<Integer>) publisher).map(Worker::addTwo);
            } else if (publisher instanceof Flowable) {
              return ((Flowable<Integer>) publisher).map(Worker::addTwo);
            }
            throw new IllegalStateException("Unknown publisher type");
          } finally {
            intermediate.finish();
            scope.close();
          }
        });

    SpanMatcher[] matchers = new SpanMatcher[3 + 2 * workItems];
    matchers[0] =
        span()
            .root()
            .operationName("trace-parent")
            .resourceName("trace-parent")
            .tags(componentTrace(), defaultTags());
    matchers[1] =
        span()
            .id(Worker.publisherParentId)
            .childOf(Worker.traceParentId)
            .operationName("publisher-parent")
            .resourceName("publisher-parent")
            .tags(defaultTags());
    matchers[2] =
        span()
            .id(Worker.intermediateId)
            .childOf(Worker.publisherParentId)
            .operationName("intermediate")
            .resourceName("intermediate")
            .tags(defaultTags());
    for (int i = 0; i < 2 * workItems; i += 2) {
      matchers[3 + i] =
          span()
              .childOf(Worker.publisherParentId)
              .operationName("addOne")
              .tags(componentTrace(), defaultTags());
      matchers[4 + i] =
          span()
              .childOf(Worker.publisherParentId)
              .operationName("addTwo")
              .tags(componentTrace(), defaultTags());
    }

    assertTraces(trace(SORT_BY_START_TIME, matchers));
  }

  // --- Schedulers ----------------------------------------------------------

  static List<Arguments> schedulerArgs() {
    return Arrays.asList(
        Arguments.of("new-thread", Schedulers.newThread()),
        Arguments.of("computation", Schedulers.computation()),
        Arguments.of("single", Schedulers.single()),
        Arguments.of("trampoline", Schedulers.trampoline()));
  }

  @ParameterizedTest(name = "Flowables produce the right number of results on ''{0}'' scheduler")
  @MethodSource("schedulerArgs")
  void schedulers(String schedulerName, Scheduler scheduler) {
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
}
