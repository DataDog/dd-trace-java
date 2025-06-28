package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

public class CompleteSpan<T> implements CoreSubscriber<T> {
  private final CoreSubscriber<T> delegate;
  private final AgentSpan span;

  public CompleteSpan(CoreSubscriber<T> delegate, AgentSpan span) {
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public Context currentContext() {
    return delegate.currentContext();
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    delegate.onSubscribe(subscription);
  }

  @Override
  public void onError(Throwable throwable) {
    delegate.onError(throwable);
    span.finish();
  }

  @Override
  public void onComplete() {
    delegate.onComplete();
    span.finish();
  }

  @Override
  public void onNext(T o) {
    delegate.onNext(o);
    span.finish();
  }
}
