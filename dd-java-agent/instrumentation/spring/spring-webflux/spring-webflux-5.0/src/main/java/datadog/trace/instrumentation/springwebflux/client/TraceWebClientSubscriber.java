package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.DECORATE;

import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Subscription;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

public final class TraceWebClientSubscriber implements CoreSubscriber<ClientResponse> {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();

  final CoreSubscriber<? super ClientResponse> actual;

  final Context context;

  private final AgentSpan span;
  private final AgentSpan parent;

  public TraceWebClientSubscriber(
      final CoreSubscriber<? super ClientResponse> actual,
      final AgentSpan span,
      final AgentSpan parent) {
    this.actual = actual;
    this.span = span;
    this.parent = parent != null ? parent : noopSpan();
    context = actual.currentContext();
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    try (final ContextScope scope = activateSpan(parent)) {
      actual.onSubscribe(subscription);
    }
  }

  @Override
  public void onNext(final ClientResponse response) {
    // Without a transport span, callbacks may wait for this span to finish.
    if (OTEL_SEMANTICS) {
      finishSpan(response);
    }
    try (final ContextScope scope = activateSpan(parent)) {
      actual.onNext(response);
    } finally {
      if (!OTEL_SEMANTICS) {
        finishSpan(response);
      }
    }
  }

  @Override
  public void onError(final Throwable t) {
    if (OTEL_SEMANTICS) {
      finishSpan(t);
    }
    try (final ContextScope scope = activateSpan(parent)) {
      actual.onError(t);
    } finally {
      if (!OTEL_SEMANTICS) {
        finishSpan(t);
      }
    }
  }

  @Override
  public void onComplete() {

    try (final ContextScope scope = activateSpan(parent)) {
      actual.onComplete();
    }
  }

  public void onCancel() {
    DECORATE.onCancel(span);
    span.finish();
  }

  @Override
  public Context currentContext() {
    return context;
  }

  private void finishSpan(final ClientResponse response) {
    DECORATE.onResponse(span, response);
    DECORATE.beforeFinish(span);
    span.finish();
  }

  private void finishSpan(final Throwable error) {
    DECORATE.onError(span, error);
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
