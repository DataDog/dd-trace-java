package testdog.trace.instrumentation.reactor.core;

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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReactorCoreTest extends AbstractInstrumentationTest {

  static final String EXCEPTION_MESSAGE = "test exception";

  // The component tag is stored as a UTF8BytesString, so we compare by string content rather than
  // using is("trace") which would fail the asymmetric String#equals(UTF8BytesString) check.
  static TagsMatcher componentTrace() {
    return tag(Tags.COMPONENT, validates(o -> "trace".equals(String.valueOf(o))));
  }

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
      AgentScope scope = activateSpan(span);

      Publisher<Integer> publisher = (Publisher<Integer>) publisherSupplier.get();
      try {
        if (publisher instanceof Mono) {
          return ((Mono<Integer>) publisher).block();
        } else if (publisher instanceof Flux) {
          return ((Flux<Integer>) publisher).collectList().block().toArray(new Integer[0]);
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

      Publisher<?> publisher = (Publisher<?>) publisherSupplier.get();
      try {
        publisher.subscribe(
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
      } finally {
        scope.close();
        span.finish();
      }
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
            "basic mono",
            new Object[] {2},
            1,
            (Supplier<Object>) () -> Mono.just(1).map(Worker::addOne)),
        Arguments.of(
            "two operations mono",
            new Object[] {4},
            2,
            (Supplier<Object>) () -> Mono.just(2).map(Worker::addOne).map(Worker::addOne)),
        Arguments.of(
            "delayed mono",
            new Object[] {4},
            1,
            (Supplier<Object>)
                () -> Mono.just(3).delayElement(Duration.ofMillis(100)).map(Worker::addOne)),
        Arguments.of(
            "delayed twice mono",
            new Object[] {6},
            2,
            (Supplier<Object>)
                () ->
                    Mono.just(4)
                        .delayElement(Duration.ofMillis(100))
                        .map(Worker::addOne)
                        .delayElement(Duration.ofMillis(100))
                        .map(Worker::addOne)),
        Arguments.of(
            "basic flux",
            new Object[] {6, 7},
            2,
            (Supplier<Object>) () -> Flux.fromIterable(Arrays.asList(5, 6)).map(Worker::addOne)),
        Arguments.of(
            "two operations flux",
            new Object[] {8, 9},
            4,
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(6, 7)).map(Worker::addOne).map(Worker::addOne)),
        Arguments.of(
            "delayed flux",
            new Object[] {8, 9},
            2,
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(7, 8))
                        .delayElements(Duration.ofMillis(100))
                        .map(Worker::addOne)),
        Arguments.of(
            "delayed twice flux",
            new Object[] {10, 11},
            4,
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(8, 9))
                        .delayElements(Duration.ofMillis(100))
                        .map(Worker::addOne)
                        .delayElements(Duration.ofMillis(100))
                        .map(Worker::addOne)),
        Arguments.of(
            "mono from callable",
            new Object[] {12},
            2,
            (Supplier<Object>)
                () -> Mono.fromCallable(() -> Worker.addOne(10)).map(Worker::addOne)));
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
            "mono", (Supplier<Object>) () -> Mono.error(new RuntimeException(EXCEPTION_MESSAGE))),
        Arguments.of(
            "flux", (Supplier<Object>) () -> Flux.error(new RuntimeException(EXCEPTION_MESSAGE))));
  }

  @ParameterizedTest(name = "Publisher error ''{0}'' test")
  @MethodSource("publisherErrorArgs")
  void publisherError(String name, Supplier<Object> supplier) {
    RuntimeException exception =
        assertThrows(RuntimeException.class, () -> Worker.assemblePublisherUnderTrace(supplier));
    assertEquals(EXCEPTION_MESSAGE, exception.getMessage());

    // It's important that we don't attach errors at the Reactor level so that we don't
    // impact the spans on reactor integrations such as netty and lettuce, as reactor is
    // more of a context propagation mechanism than something we would be tracking for
    // errors — this is ok.
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
            "basic mono failure",
            1,
            (Supplier<Object>)
                () -> Mono.just(1).map(Worker::addOne).map(i -> Worker.throwException())),
        Arguments.of(
            "basic flux failure",
            1,
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(5, 6))
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
        Arguments.of("basic mono", (Supplier<Object>) () -> Mono.just(1)),
        Arguments.of(
            "basic flux", (Supplier<Object>) () -> Flux.fromIterable(Arrays.asList(5, 6))));
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
            "basic mono",
            3,
            (Supplier<Object>)
                () ->
                    Mono.just(1)
                        .map(Worker::addOne)
                        .map(Worker::addOne)
                        .then(Mono.just(1).map(Worker::addOne))),
        Arguments.of(
            "basic flux",
            5,
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(5, 6))
                        .map(Worker::addOne)
                        .map(Worker::addOne)
                        .then(Mono.just(1).map(Worker::addOne))));
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

  // --- Correct parents from subscription time (block) ----------------------

  @Test
  void correctParentsFromSubscriptionTimeBlock() {
    Mono<Integer> mono = Mono.just(42).map(Worker::addOne).map(Worker::addTwo);

    Worker.runUnderTraceParent(
        () -> {
          mono.block();
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
        Arguments.of("basic mono", 1, (Supplier<Object>) () -> Mono.just(1).map(Worker::addOne)),
        Arguments.of(
            "basic flux",
            2,
            (Supplier<Object>) () -> Flux.fromIterable(Arrays.asList(1, 2)).map(Worker::addOne)));
  }

  @ParameterizedTest(
      name = "Publisher chain spans have the correct parents from subscription time ''{0}''")
  @MethodSource("subscriptionTimeIntermediateArgs")
  @SuppressWarnings("unchecked")
  void correctParentsFromSubscriptionTime(String name, int workItems, Supplier<Object> supplier) {
    Worker.assemblePublisherUnderTrace(
        () -> {
          Object publisher = supplier.get();

          AgentSpan intermediate = startSpan("test", "intermediate");
          Worker.intermediateId = intermediate.getSpanId();
          AgentScope scope = activateSpan(intermediate);
          try {
            if (publisher instanceof Mono) {
              return ((Mono<Integer>) publisher).map(Worker::addTwo);
            } else if (publisher instanceof Flux) {
              return ((Flux<Integer>) publisher).map(Worker::addTwo);
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
        Arguments.of("parallel", Schedulers.parallel()),
        Arguments.of("single", Schedulers.single()),
        Arguments.of("immediate", Schedulers.immediate()));
  }

  @ParameterizedTest(name = "Fluxes produce the right number of results on ''{0}'' scheduler")
  @MethodSource("schedulerArgs")
  void schedulers(String schedulerName, Object scheduler) {
    List<String> values =
        Flux.fromIterable(Arrays.asList(1, 2, 3, 4))
            .parallel()
            .runOn((reactor.core.scheduler.Scheduler) scheduler)
            .flatMap(num -> Mono.just(num.toString() + " on " + Thread.currentThread().getName()))
            .sequential()
            .collectList()
            .block();

    assertEquals(4, values.size());
  }

  // --- Cross-thread context propagation ------------------------------------

  static List<Arguments> crossThreadArgs() {
    return Arrays.asList(
        Arguments.of(
            "publishOn",
            (Supplier<Object>)
                () -> Flux.just(1, 2).publishOn(Schedulers.parallel()).map(Worker::addOne)),
        Arguments.of(
            "subscribeOn",
            (Supplier<Object>)
                () -> Flux.just(1, 2).subscribeOn(Schedulers.single()).map(Worker::addOne)),
        Arguments.of(
            "subscribeOn+publishOn",
            (Supplier<Object>)
                () ->
                    Flux.just(1, 2)
                        .subscribeOn(Schedulers.single())
                        .publishOn(Schedulers.parallel())
                        .map(Worker::addOne)));
  }

  @ParameterizedTest(name = "subscribe-time context propagates across threads with ''{0}''")
  @MethodSource("crossThreadArgs")
  @SuppressWarnings("unchecked")
  void crossThreadContextPropagation(String name, Supplier<Object> pipeline) {
    Worker.runUnderTraceParent(
        () -> {
          ((Flux<Integer>) pipeline.get()).collectList().block();
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
                .operationName("addOne")
                .tags(componentTrace(), defaultTags())));
  }

  // --- No spurious traces outside active trace --------------------------------

  @Test
  void noSpuriousTracesWhenAssembledOutsideTrace() {
    Mono.just(1).map(i -> i + 1).block();
    tracer.flush();
    assertEquals(
        0,
        writer.getTraceCount(),
        () -> "Unexpected traces emitted without active trace: " + writer);
  }

  // --- Mono lifecycle spans with parent ------------------------------------

  @Test
  void monoLifecycleSpansWithParent() {
    Worker.runUnderTraceParent(
        () -> {
          Mono.fromCallable(() -> Worker.addOne(0)).doOnNext(v -> Worker.addOne(v)).block();
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
                .operationName("addOne")
                .tags(componentTrace(), defaultTags())));
  }

  // --- Mono then() ---------------------------------------------------------

  @Test
  void monoThenPropagatesContext() {
    Worker.runUnderTraceParent(
        () -> {
          Mono.create(
                  sink -> {
                    Worker.addOne(0);
                    sink.success();
                  })
              .then(
                  Mono.create(
                      sink -> {
                        Worker.addOne(0);
                        sink.success();
                      }))
              .block();
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
                .operationName("addOne")
                .tags(componentTrace(), defaultTags())));
  }

  // --- windowUntil does not throw NPE on advice ----------------------------

  @Test
  void windowUntilDoesNotThrowNpe() {
    Long count = Flux.range(1, 100).windowUntil(i -> i % 10 == 0).count().block();
    assertEquals(11, count);
  }
}
