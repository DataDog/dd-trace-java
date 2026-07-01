package datadog.trace.instrumentation.httpclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public class BodyHandlerWrapper<T> implements BodyHandler<T> {
  private final BodyHandler<T> delegate;
  private final AgentSpan span;

  public BodyHandlerWrapper(BodyHandler<T> delegate, AgentSpan span) {
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public BodySubscriber<T> apply(ResponseInfo responseInfo) {
    // Capture the continuation lazily here rather than at sendAsync() call time.
    BodySubscriber<T> subscriber = delegate.apply(responseInfo);
    if (subscriber instanceof BodySubscriberWrapper) {
      return subscriber;
    }
    return new BodySubscriberWrapper<>(subscriber, captureSpan(span));
  }

  static class BodySubscriberWrapper<T> implements BodySubscriber<T> {
    private final BodySubscriber<T> delegate;
    private final AgentScope.Continuation continuation;

    public BodySubscriberWrapper(BodySubscriber<T> delegate, AgentScope.Continuation continuation) {
      this.delegate = delegate;
      this.continuation = continuation;
    }

    public BodySubscriber<T> getDelegate() {
      return delegate;
    }

    @Override
    public CompletionStage<T> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      try (AgentScope ignore = continuation.activate()) {
        delegate.onNext(item);
      }
    }

    @Override
    public void onError(Throwable throwable) {
      try (AgentScope ignore = continuation.activate()) {
        delegate.onError(throwable);
      }
    }

    @Override
    public void onComplete() {
      try (AgentScope ignore = continuation.activate()) {
        delegate.onComplete();
      }
    }
  }
}
