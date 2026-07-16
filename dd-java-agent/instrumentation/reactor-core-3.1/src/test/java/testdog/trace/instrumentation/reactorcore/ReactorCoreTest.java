package testdog.trace.instrumentation.reactorcore;

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
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
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

      Object publisher = publisherSupplier.get();
      try {
        if (publisher instanceof Mono) {
          return ((Mono<Object>) publisher).block();
        } else if (publisher instanceof Flux) {
          List<Object> list = ((Flux<Object>) publisher).collectList().block();
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
      Flux<?> flux;
      if (publisher instanceof Mono) {
        flux = ((Mono<?>) publisher).flux();
      } else {
        flux = (Flux<?>) publisher;
      }

      try {
        flux.subscribe(
            new org.reactivestreams.Subscriber<Object>() {
              @Override
              public void onSubscribe(org.reactivestreams.Subscription subscription) {
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
                        .concatWith(Mono.just(1).map(Worker::addOne))),
        Arguments.of(
            "basic flux",
            5,
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(5, 6))
                        .map(Worker::addOne)
                        .map(Worker::addOne)
                        .concatWith(Mono.just(1).map(Worker::addOne))));
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
    // Note: Schedulers.elastic() was deprecated in Reactor 3.4 and removed in 3.5+, so
    // it was omitted here to keep the base test set compilable against both the module's
    // declared min (3.1) and the latestDep version. See the PR body for the research
    // observation about how the toolkit could handle latestDep API-drift better —
    // master splits Reactor version-sensitive tests into a separate `latestDepTest`
    // source set.
    return Arrays.asList(
        Arguments.of("parallel", Schedulers.parallel()),
        Arguments.of("single", Schedulers.single()));
  }

  @ParameterizedTest(name = "Flux produces the right number of results on ''{0}'' scheduler")
  @MethodSource("schedulerArgs")
  void schedulers(String schedulerName, Scheduler scheduler) {
    List<String> values =
        Flux.fromIterable(Arrays.asList(1, 2, 3, 4))
            .parallel()
            .runOn(scheduler)
            .flatMap(num -> Mono.just(num.toString() + " on " + Thread.currentThread().getName()))
            .sequential()
            .collectList()
            .block();

    assertEquals(4, values.size());

    // No trace-parent span is active while the chain is assembled, so the instrumentation must be
    // non-intrusive: parallel scheduler hops must not synthesize any trace.
    tracer.flush();
    assertEquals(
        0,
        writer.getTraceCount(),
        () -> "Unexpected traces emitted without active trace: " + writer);
  }

  @ParameterizedTest(name = "Flux propagates context on ''{0}'' scheduler")
  @MethodSource("schedulerArgs")
  void fluxParallelContextPropagation(String schedulerName, Scheduler scheduler) {
    Worker.assemblePublisherUnderTrace(
        () ->
            Flux.fromIterable(Arrays.asList(1, 2, 3, 4))
                .parallel()
                .runOn(scheduler)
                .flatMap(num -> Mono.just(num).map(Worker::addOne))
                .sequential());

    SpanMatcher[] matchers = new SpanMatcher[6];
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
    for (int i = 0; i < 4; i++) {
      matchers[2 + i] =
          span()
              .childOf(Worker.publisherParentId)
              .operationName("addOne")
              .resourceName("addOne")
              .tags(componentTrace(), defaultTags());
    }

    assertTraces(trace(SORT_BY_START_TIME, matchers));
  }

  // --- No spurious traces outside active trace --------------------------------

  static List<Arguments> noSpuriousTracesArgs() {
    return Arrays.asList(
        Arguments.of(
            "flux",
            (Supplier<Object>)
                () ->
                    Flux.fromIterable(Arrays.asList(1, 2, 3, 4))
                        .map(i -> i + 1)
                        .collectList()
                        .block()),
        Arguments.of("mono", (Supplier<Object>) () -> Mono.just(1).map(i -> i + 1).block()));
  }

  @ParameterizedTest(name = "No spurious traces for ''{0}'' assembled outside active trace")
  @MethodSource("noSpuriousTracesArgs")
  void noSpuriousTracesWhenAssembledOutsideTrace(String name, Supplier<Object> supplier) {
    supplier.get();
    tracer.flush();
    assertEquals(
        0,
        writer.getTraceCount(),
        () -> "Unexpected traces emitted without active trace: " + writer);
  }
}
