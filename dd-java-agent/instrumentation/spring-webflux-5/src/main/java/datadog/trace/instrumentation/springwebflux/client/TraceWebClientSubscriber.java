package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Subscription;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

public final class TraceWebClientSubscriber implements CoreSubscriber<ClientResponse> {

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
    try (final AgentScope scope = activateSpan(parent)) {
      actual.onSubscribe(subscription);
    }
  }

  @Override
  public void onNext(final ClientResponse response) {
    try (final AgentScope scope = activateSpan(parent)) {
      actual.onNext(response);
    } finally {
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onError(final Throwable t) {
    try (final AgentScope scope = activateSpan(parent)) {
      actual.onError(t);
    } finally {
      DECORATE.onError(span, t);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onComplete() {

    try (final AgentScope scope = activateSpan(parent)) {
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
}
