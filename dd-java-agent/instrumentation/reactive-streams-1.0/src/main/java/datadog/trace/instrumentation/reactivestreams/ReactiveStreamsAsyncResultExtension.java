package datadog.trace.instrumentation.reactivestreams;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.EagerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtension;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ReactiveStreamsAsyncResultExtension implements AsyncResultExtension, EagerHelper {
  static {
    AsyncResultExtensions.register(new ReactiveStreamsAsyncResultExtension());
  }

  /**
   * Register the extension as an {@link AsyncResultExtension} using static class initialization.
   * <br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once under {@link
   * AsyncResultExtensions}.
   */
  public static void init() {}

  @Override
  public boolean supports(Class<?> result) {
    boolean ret = result == Publisher.class;
    return ret;
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result != null) {
      // the span will be closed then the subscriber span will finish.
      return new WrappedPublisher<>((Publisher<?>) result, span);
    }
    return null;
  }

  private static class WrappedPublisher<T> implements Publisher<T> {
    private final Publisher<T> delegate;
    private final AgentSpan span;

    public WrappedPublisher(Publisher<T> delegate, AgentSpan span) {
      this.delegate = delegate;
      this.span = span;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
      this.delegate.subscribe(new WrappedSubscriber<>(s, this.span));
    }
  }

  private static class WrappedSubscriber<T> implements Subscriber<T> {
    private final Subscriber<T> delegate;
    private final AgentSpan span;

    public WrappedSubscriber(Subscriber<T> delegate, AgentSpan span) {
      this.delegate = delegate;
      this.span = span;
    }

    @Override
    public void onSubscribe(Subscription s) {
      this.delegate.onSubscribe(new WrappedSubscription(s, this.span));
    }

    @Override
    public void onNext(T t) {
      this.delegate.onNext(t);
    }

    @Override
    public void onError(Throwable t) {
      this.span.addThrowable(t);
      this.span.finish();
      this.delegate.onError(t);
    }

    @Override
    public void onComplete() {
      this.span.finish();
      this.delegate.onComplete();
    }
  }

  private static class WrappedSubscription implements Subscription {
    private final Subscription delegate;
    private final AgentSpan span;

    public WrappedSubscription(Subscription delegate, AgentSpan span) {
      this.delegate = delegate;
      this.span = span;
    }

    @Override
    public void request(long n) {
      delegate.request(n);
    }

    @Override
    public void cancel() {
      span.finish();
      delegate.cancel();
    }
  }
}
