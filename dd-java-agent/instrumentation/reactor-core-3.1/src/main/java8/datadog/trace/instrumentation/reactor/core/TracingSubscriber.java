package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

/**
 * Based on OpenTracing code.
 * https://github.com/opentracing-contrib/java-reactor/blob/master/src/main/java/io/opentracing/contrib/reactor/TracedSubscriber.java
 */
public class TracingSubscriber<T> implements CoreSubscriber<T> {
  private final Subscriber<? super T> subscriber;
  private final Context context;
  private final AgentSpan span;

  public TracingSubscriber(
      final Subscriber<? super T> subscriber, final Context context, final AgentSpan span) {
    this.subscriber = subscriber;
    this.context = context;
    this.span = span;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T o) {
    try (AgentScope scope = activateSpan(span)) {
      subscriber.onNext(o);
    }
  }

  @Override
  public void onError(final Throwable throwable) {
    try (AgentScope scope = activateSpan(span)) {
      subscriber.onError(throwable);
    }
  }

  @Override
  public void onComplete() {
    try (AgentScope scope = activateSpan(span)) {
      subscriber.onComplete();
    }
  }

  @Override
  public Context currentContext() {
    return context;
  }
}
