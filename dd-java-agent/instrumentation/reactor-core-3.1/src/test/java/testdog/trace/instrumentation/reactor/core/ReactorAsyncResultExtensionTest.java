package testdog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import annotatedsample.ReactorTracedMethods;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WithConfig(key = "trace.otel.enabled", value = "true")
class ReactorAsyncResultExtensionTest extends AbstractInstrumentationTest {

  static final String EXCEPTION_MESSAGE = "Test exception";

  // The COMPONENT and SPAN_KIND tags are stored as UTF8BytesString, so we compare by string content
  // rather than using is("...") which would fail the asymmetric String#equals(UTF8BytesString)
  // check.
  static TagsMatcher otelComponent() {
    return tag(Tags.COMPONENT, validates(o -> "opentelemetry".equals(String.valueOf(o))));
  }

  static TagsMatcher internalSpanKind() {
    return tag(Tags.SPAN_KIND, validates(o -> Tags.SPAN_KIND_INTERNAL.equals(String.valueOf(o))));
  }

  // The operation and resource names are stored as UTF8BytesString, so we compare by string content
  // (CharSequence equality is asymmetric: String#equals(UTF8BytesString) is false).
  static SpanMatcher otelSpan(String name) {
    return span()
        .operationName(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)))
        .resourceName((CharSequence cs) -> name.contentEquals(cs));
  }

  // --- Mono success --------------------------------------------------------

  @Test
  void withSpanAnnotatedAsyncMono() {
    CountDownLatch latch = new CountDownLatch(1);
    Mono<String> mono = ReactorTracedMethods.traceAsyncMono(latch);

    // The span must not be finished before the async result completes.
    assertEquals(0, writer.size());

    latch.countDown();
    mono.block();

    assertTraces(
        trace(
            otelSpan("ReactorTracedMethods.traceAsyncMono")
                .tags(defaultTags(), otelComponent(), internalSpanKind())));
  }

  // --- Mono failure --------------------------------------------------------

  @Test
  void withSpanAnnotatedAsyncFailingMono() {
    CountDownLatch latch = new CountDownLatch(1);
    IllegalStateException expectedException = new IllegalStateException(EXCEPTION_MESSAGE);
    Mono<String> mono = ReactorTracedMethods.traceAsyncFailingMono(latch, expectedException);

    assertEquals(0, writer.size());

    latch.countDown();
    assertThrows(IllegalStateException.class, mono::block);

    assertTraces(
        trace(
            otelSpan("ReactorTracedMethods.traceAsyncFailingMono")
                .error()
                .tags(
                    defaultTags(),
                    otelComponent(),
                    internalSpanKind(),
                    error(IllegalStateException.class, EXCEPTION_MESSAGE))));
  }

  // --- Mono cancellation ---------------------------------------------------

  @Test
  void withSpanAnnotatedAsyncCancelledMono() {
    CountDownLatch latch = new CountDownLatch(1);
    Mono<String> mono = ReactorTracedMethods.traceAsyncMono(latch);

    assertEquals(0, writer.size());

    latch.countDown();
    mono.subscribe(new ReactorTracedMethods.CancelSubscriber<String>());

    assertTraces(
        trace(
            otelSpan("ReactorTracedMethods.traceAsyncMono")
                .tags(defaultTags(), otelComponent(), internalSpanKind())));
  }

  // --- Flux success --------------------------------------------------------

  @Test
  void withSpanAnnotatedAsyncFlux() {
    CountDownLatch latch = new CountDownLatch(1);
    Flux<String> flux = ReactorTracedMethods.traceAsyncFlux(latch);

    assertEquals(0, writer.size());

    latch.countDown();
    flux.blockLast();

    assertTraces(
        trace(
            otelSpan("ReactorTracedMethods.traceAsyncFlux")
                .tags(defaultTags(), otelComponent(), internalSpanKind())));
  }

  // --- Flux failure --------------------------------------------------------

  @Test
  void withSpanAnnotatedAsyncFailingFlux() {
    CountDownLatch latch = new CountDownLatch(1);
    IllegalStateException expectedException = new IllegalStateException(EXCEPTION_MESSAGE);
    Flux<String> flux = ReactorTracedMethods.traceAsyncFailingFlux(latch, expectedException);

    assertEquals(0, writer.size());

    latch.countDown();
    assertThrows(IllegalStateException.class, flux::blockLast);

    assertTraces(
        trace(
            otelSpan("ReactorTracedMethods.traceAsyncFailingFlux")
                .error()
                .tags(
                    defaultTags(),
                    otelComponent(),
                    internalSpanKind(),
                    error(IllegalStateException.class, EXCEPTION_MESSAGE))));
  }

  // --- Flux cancellation ---------------------------------------------------

  @Test
  void withSpanAnnotatedAsyncCancelledFlux() {
    CountDownLatch latch = new CountDownLatch(1);
    Flux<String> flux = ReactorTracedMethods.traceAsyncFlux(latch);

    assertEquals(0, writer.size());

    latch.countDown();
    flux.subscribe(new ReactorTracedMethods.CancelSubscriber<String>());

    assertTraces(
        trace(
            otelSpan("ReactorTracedMethods.traceAsyncFlux")
                .tags(defaultTags(), otelComponent(), internalSpanKind())));
  }
}
