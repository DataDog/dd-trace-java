package datadog.trace.instrumentation.rxjava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
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
  private final AgentSpan parent;
  private final BaseDecorator decorator;

  public TracedOnSubscribe(
      final Observable originalObservable,
      final CharSequence operationName,
      final BaseDecorator decorator) {
    delegate = DDTracingUtil.extractOnSubscribe(originalObservable);
    this.operationName = operationName;
    this.decorator = decorator;
    this.parent = activeSpan();
  }

  @Override
  public void call(final Subscriber<? super T> subscriber) {
    final AgentSpan span = startSpan(operationName, parent != null ? parent.context() : null);
    afterStart(span);

    try (final AgentScope scope = activateSpan(span)) {
      delegate.call(new TracedSubscriber(span, subscriber, decorator));
    }
  }

  protected void afterStart(final AgentSpan span) {
    decorator.afterStart(span);
  }
}
