package test;

import datadog.trace.api.Trace;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class ReactorTest {

  @Trace(operationName = "child", resourceName = "child")
  private String doSomeMapping(String s) {
    try {
      // simulate some work
      Thread.sleep(500 + new Random(System.currentTimeMillis()).nextInt(1000));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    return s;
  }

  @Trace(operationName = "mono", resourceName = "mono")
  private Mono<String> monoMethod() {
    // This mono will complete when the delay is expired
    return Mono.delay(Duration.ofSeconds(1)).map(ignored -> "Hello World");
  }

  @Trace(operationName = "mono", resourceName = "mono")
  private Mono<String> monoMethodDownstreamPropagate() {
    // here the active span is the one created by the @Trace annotation before the method executes
    return Mono.just("Hello World").contextWrite(Context.of("dd.span", Span.current()));
  }

  private <T> Mono<T> tracedMono(
      final Tracer tracer, final String spanName, Span parentSpan, final Mono<T> mono) {
    SpanBuilder spanBuilder = tracer.spanBuilder(spanName);
    if (parentSpan != null) {
      spanBuilder.setParent(io.opentelemetry.context.Context.current().with(parentSpan));
    }
    final Span span = spanBuilder.startSpan();
    return mono //
        .contextWrite(Context.of("dd.span", span))
        .doFinally(ignored -> span.end());
  }

  @Test
  public void testSimpleUpstreamPropagation() {
    final Tracer tracer = GlobalOpenTelemetry.getTracer("");
    final Span parent = tracer.spanBuilder("parent").startSpan();
    assert io.opentelemetry.context.Context.current() == io.opentelemetry.context.Context.root();
    try (final Scope parentScope = parent.makeCurrent()) {
      // monoMethod will start a trace when called but that span will complete only when the
      // returned mono completes.
      // doSomeMapping will open a span that's child of parent because it's the active one when we
      // subscribe
      assert Objects.equals(monoMethod().map(this::doSomeMapping).block(), "Hello World");
    } finally {
      parent.end();
    }
  }

  @Test
  public void testSimpleDownstreamPropagation() {
    final Tracer tracer = GlobalOpenTelemetry.getTracer("");
    final Span parent = tracer.spanBuilder("parent").startSpan();
    assert io.opentelemetry.context.Context.current() == io.opentelemetry.context.Context.root();
    try (final Scope parentScope = parent.makeCurrent()) {
      // monoMethod will start a trace when called but that span will complete only when the
      // returned mono completes.
      // doSomeMapping will open a span that's child of parent because it's the active one when we
      // subscribe
      assert Objects.equals(
          Mono.defer(this::monoMethodDownstreamPropagate).map(this::doSomeMapping).block(),
          "Hello World");
    } finally {
      parent.end();
    }
  }

  @Test
  public void testComplexDownstreamPropagation() {
    final Tracer tracer = GlobalOpenTelemetry.getTracer("");
    final Span parent = tracer.spanBuilder("parent").startSpan();
    assert io.opentelemetry.context.Context.current() == io.opentelemetry.context.Context.root();

    Mono<String> mono =
        // here we have no active span. when the mono is emitted we propagate the context captured
        // onSubscribe
        Mono.just("Hello World") //
            // change the downstream propagated span to that new one called 'first'
            // first will be child of parent since parent was captured onSubscribe
            // (when block is called) and propagated upstream
            .flatMap(s -> tracedMono(tracer, "first", null, Mono.just(s + ", GoodBye ")))
            // map will use the active one (first) hence the child will be under first
            .map(this::doSomeMapping)
            // we change again the downstream active span to 'second' that's child of 'first'
            .flatMap(
                s ->
                    tracedMono(
                        tracer, "second", null, Mono.create(sink -> sink.success(s + "World"))))
            // now we let the downstream propagate third child of parent
            .flatMap(s -> tracedMono(tracer, "third", parent, Mono.just(s + "!")))
            // third is the active span downstream
            .map(this::doSomeMapping) // will create a child span having third as parent
            .doOnNext(System.out::println);
    try (final Scope parentScope = parent.makeCurrent()) {
      // block, like subscribe will capture the current scope (parent here) and propagate upstream
      assert Objects.equals(mono.block(), "Hello World, GoodBye World!");
    } finally {
      parent.end();
    }
  }
}
