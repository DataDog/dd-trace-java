package datadog.trace.instrumentation.rxjava2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Wrapper that makes sure spans from subscriber events treat the captured span as their parent. */
public final class TracingSubscriber<T> implements Subscriber<T> {
  private final Subscriber<T> subscriber;
  private final AgentSpan parentSpan;

  public TracingSubscriber(final Subscriber<T> subscriber, final AgentSpan parentSpan) {
    this.subscriber = subscriber;
    this.parentSpan = parentSpan;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T value) {
    try (final AgentScope scope = activateSpan(parentSpan)) {
      subscriber.onNext(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final AgentScope scope = activateSpan(parentSpan)) {
      subscriber.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final AgentScope scope = activateSpan(parentSpan)) {
      subscriber.onComplete();
    }
  }
}
