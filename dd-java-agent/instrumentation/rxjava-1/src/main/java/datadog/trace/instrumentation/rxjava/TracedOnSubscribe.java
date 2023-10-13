package datadog.trace.instrumentation.rxjava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.capture;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import rx.DDTracingUtil;
import rx.Observable;
import rx.Subscriber;

public class TracedOnSubscribe<T> implements Observable.OnSubscribe<T> {

  private final Observable.OnSubscribe<?> delegate;
  private final CharSequence operationName;
  private final AgentScope.Continuation continuation;
  private final BaseDecorator decorator;

  public TracedOnSubscribe(
      final Observable originalObservable,
      final CharSequence operationName,
      final BaseDecorator decorator) {
    delegate = DDTracingUtil.extractOnSubscribe(originalObservable);
    this.operationName = operationName;
    this.decorator = decorator;

    continuation = capture();
  }

  @Override
  public void call(final Subscriber<? super T> subscriber) {
    final AgentSpan span; // span finished by TracedSubscriber
    if (continuation != null) {
      try (final AgentScope scope = continuation.activate()) {
        span = startSpan(operationName);
      }
    } else {
      span = startSpan(operationName);
    }

    afterStart(span);

    try (final AgentScope scope = activateSpan(span)) {
      scope.setAsyncPropagation(true);
      delegate.call(new TracedSubscriber(span, subscriber, decorator));
    }
  }

  protected void afterStart(final AgentSpan span) {
    decorator.afterStart(span);
  }
}
