package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.r2dbc.spi.Result;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Wraps a {@link Publisher} returned by {@code Statement.execute()} or {@code Batch.execute()} to
 * finish the associated span when the publisher signals completion or error.
 */
public final class TracingPublisher implements Publisher<Result> {

  private final Publisher<? extends Result> delegate;
  private final AgentSpan span;

  public TracingPublisher(Publisher<? extends Result> delegate, AgentSpan span) {
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public void subscribe(Subscriber<? super Result> subscriber) {
    delegate.subscribe(new TracingSubscriber(subscriber, span));
  }

  private static final class TracingSubscriber implements Subscriber<Result> {

    private final Subscriber<? super Result> delegate;
    private final AgentSpan span;

    TracingSubscriber(Subscriber<? super Result> delegate, AgentSpan span) {
      this.delegate = delegate;
      this.span = span;
    }

    @Override
    public void onSubscribe(Subscription s) {
      delegate.onSubscribe(s);
    }

    @Override
    public void onNext(Result result) {
      delegate.onNext(result);
    }

    @Override
    public void onError(Throwable t) {
      try {
        DECORATE.onError(span, t);
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        delegate.onError(t);
      }
    }

    @Override
    public void onComplete() {
      try {
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        delegate.onComplete();
      }
    }
  }
}
