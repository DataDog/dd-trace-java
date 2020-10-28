package datadog.trace.instrumentation.reactor.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
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

  public TracingSubscriber(final Subscriber<? super T> subscriber, final Context context) {
    this(subscriber, context, AgentTracer.activeSpan());
  }

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
    withActiveSpan(() -> subscriber.onNext(o));
  }

  @Override
  public void onError(final Throwable throwable) {
    withActiveSpan(() -> subscriber.onError(throwable));
  }

  @Override
  public void onComplete() {
    withActiveSpan(subscriber::onComplete);
  }

  @Override
  public Context currentContext() {
    return context;
  }

  private void withActiveSpan(final Runnable runnable) {
    if (span != null) {
      try (final TraceScope scope = AgentTracer.activateSpan(span)) {
        scope.setAsyncPropagation(true);
        runnable.run();
      }
    } else {
      runnable.run();
    }
  }
}
