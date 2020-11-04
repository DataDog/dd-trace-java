package datadog.trace.instrumentation.rxjava2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public final class TracingSubscriber<T> implements Subscriber<T> {
  private final Subscriber<T> subscriber;
  private final AgentSpan span;

  public TracingSubscriber(final Subscriber<T> subscriber, final AgentSpan span) {
    this.subscriber = subscriber;
    this.span = span;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T value) {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      subscriber.onNext(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      subscriber.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      subscriber.onComplete();
    }
  }
}
