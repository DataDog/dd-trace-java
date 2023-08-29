package datadog.trace.instrumentation.reactivestreams;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator.AsyncResultSupportExtension;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ReactiveStreamsAsyncResultSupportExtension implements AsyncResultSupportExtension {
  static {
    AsyncResultDecorator.registerExtension(new ReactiveStreamsAsyncResultSupportExtension());
  }

  /**
   * Register the extension as an {@link AsyncResultSupportExtension} using static class
   * initialization.<br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once to the
   * {@link AsyncResultDecorator}.
   */
  public static void initialize() {}

  @Override
  public boolean supports(Class<?> result) {
    return Publisher.class.isAssignableFrom(result);
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result instanceof Publisher) {
      return new WrappedPublisher<>((Publisher) result, span);
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
      this.delegate.onSubscribe(s);
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
}
