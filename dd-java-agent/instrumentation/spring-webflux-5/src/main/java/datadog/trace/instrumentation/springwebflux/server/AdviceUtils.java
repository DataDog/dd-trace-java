package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.springwebflux.server.SpringWebfluxHttpServerDecorator.DECORATE;

import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

public final class AdviceUtils {

  public static final String SPAN_ATTRIBUTE = "datadog.trace.instrumentation.springwebflux.Span";
  public static final String PARENT_SPAN_ATTRIBUTE =
      "datadog.trace.instrumentation.springwebflux.ParentSpan";

  private static final ClassValue<CharSequence> NAMES =
      GenericClassValue.of(
          type -> {
            String name = type.getName();
            int lambdaIdx = name.lastIndexOf("$$Lambda");
            if (lambdaIdx > -1) {
              return UTF8BytesString.create(
                  name.substring(name.lastIndexOf('.') + 1, lambdaIdx) + ".lambda");
            } else {
              return DECORATE.spanNameForMethod(type, "handle");
            }
          });

  public static CharSequence constructOperationName(Object handler) {
    return NAMES.get(handler.getClass());
  }

  public static <T> Mono<T> setPublisherSpan(Mono<T> mono, AgentSpan span) {
    return mono.<T>transform(finishSpanNextOrError(span));
  }

  public static <T> Mono<T> wrapMonoWithScope(Mono<T> mono, AgentSpan span) {
    return mono.<T>transform(wrapPublisher(span));
  }

  /**
   * Idea for this has been lifted from https://github.com/reactor/reactor-core/issues/947. Newer
   * versions of reactor-core have easier way to access context but we want to support older
   * versions.
   */
  private static <T> Function<? super Publisher<T>, ? extends Publisher<T>> finishSpanNextOrError(
      AgentSpan span) {
    return Operators.lift(
        (scannable, subscriber) -> new SpanFinishingSubscriber<>(subscriber, span));
  }

  private static <T> Function<? super Publisher<T>, ? extends Publisher<T>> wrapPublisher(
      AgentSpan span) {
    return Operators.lift((scannable, subscriber) -> new SpanSubscriber<>(subscriber, span));
  }

  private static class SpanSubscriber<T> implements CoreSubscriber<T>, Subscription {
    private final CoreSubscriber<? super T> subscriber;
    protected final AgentSpan span;
    private final Context context;
    private volatile Subscription subscription;

    public SpanSubscriber(CoreSubscriber<? super T> subscriber, AgentSpan span) {
      this.subscriber = subscriber;
      this.span = span;
      this.context = subscriber.currentContext().put(AgentSpan.class, span);
    }

    @Override
    public void onSubscribe(Subscription s) {
      this.subscription = s;
      try (AgentScope scope = activateSpan(span)) {
        subscriber.onSubscribe(this);
      }
    }

    @Override
    public void onNext(T t) {
      try (AgentScope scope = activateSpan(span)) {
        subscriber.onNext(t);
      }
    }

    @Override
    public void onError(Throwable t) {
      subscriber.onError(t);
    }

    @Override
    public void onComplete() {
      subscriber.onComplete();
    }

    @Override
    public Context currentContext() {
      return context;
    }

    @Override
    public void request(long n) {
      subscription.request(n);
    }

    @Override
    public void cancel() {
      subscription.cancel();
    }
  }

  /**
   * This makes sure any callback is wrapped in suspend/resume checkpoints. Otherwise, we may end up
   * executing these callbacks in different threads without being resumed first.
   */
  private static final class SpanFinishingSubscriber<T> extends SpanSubscriber<T> {
    private volatile int completed;

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<SpanFinishingSubscriber> COMPLETED =
        AtomicIntegerFieldUpdater.newUpdater(SpanFinishingSubscriber.class, "completed");

    public SpanFinishingSubscriber(CoreSubscriber<? super T> subscriber, AgentSpan span) {
      super(subscriber, span);
    }

    @Override
    public void onError(Throwable t) {
      if (null != span && COMPLETED.compareAndSet(this, 0, 1)) {
        span.setError(true);
        span.addThrowable(t);
        span.finish();
      }
      super.onError(t);
    }

    @Override
    public void onComplete() {
      if (null != span && COMPLETED.compareAndSet(this, 0, 1)) {
        span.finish();
      }
      super.onComplete();
    }

    @Override
    public void cancel() {
      if (null != span && COMPLETED.compareAndSet(this, 0, 1)) {
        span.finish();
      }
      super.cancel();
    }
  }
}
