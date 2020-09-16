package datadog.trace.instrumentation.reactor.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

/**
 * Based on OpenTracing code.
 * https://github.com/opentracing-contrib/java-reactor/blob/master/src/main/java/io/opentracing/contrib/reactor/TracedSubscriber.java
 */
@Slf4j
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
    log.debug("onSubscribe subscriber={} subscription={}", this, subscription);
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T o) {
    log.debug("onNext subscriber={}", this);
    withActiveSpan(() -> subscriber.onNext(o));
  }

  @Override
  public void onError(final Throwable throwable) {
    log.debug("onError subscriber={}", this);
    withActiveSpan(() -> subscriber.onError(throwable));
  }

  @Override
  public void onComplete() {
    log.debug("onComplete subscriber={}", this);
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
