package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.instrumentation.reactor.netty.CaptureConnectSpan.CONNECT_SPAN;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.netty.Connection;
import reactor.util.context.Context;

public final class ConnectSpanSubscriber implements CoreSubscriber<Connection> {

  private final CoreSubscriber<? super Connection> actual;
  private final AgentSpan span;

  public ConnectSpanSubscriber(
      final CoreSubscriber<? super Connection> actual, final AgentSpan span) {
    this.actual = actual;
    this.span = span;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    actual.onSubscribe(subscription);
  }

  @Override
  public void onNext(final Connection connection) {
    actual.onNext(connection);
  }

  @Override
  public void onError(final Throwable throwable) {
    actual.onError(throwable);
  }

  @Override
  public void onComplete() {
    actual.onComplete();
  }

  @Override
  public Context currentContext() {
    return actual.currentContext().put(CONNECT_SPAN, span);
  }
}
