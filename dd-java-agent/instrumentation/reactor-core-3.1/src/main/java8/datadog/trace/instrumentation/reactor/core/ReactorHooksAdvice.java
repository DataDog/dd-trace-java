package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.AgentTracer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

public class ReactorHooksAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void postInit() {
    Hooks.onEachOperator(ReactorHooksAdvice.class.getName(), tracingOperator());
  }

  public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> tracingOperator() {
    //    return Operators.lift(
    //        s -> !(s instanceof Fuseable.ScalarCallable), (s, sub) -> tracingSubscriber(sub));
    return Operators.lift((s, sub) -> tracingSubscriber(s, sub));
  }

  public static <T> CoreSubscriber<? super T> tracingSubscriber(
      final Scannable scannable, final CoreSubscriber<T> delegate) {
    if (scannable instanceof Fuseable.ScalarCallable || scannable instanceof TracingSubscriber) {
      return delegate;
    }

    final Context context = delegate.currentContext();
    final Optional<AgentSpan> maybeSpan = context.getOrEmpty(AgentSpan.class);
    final AgentSpan span = maybeSpan.orElseGet(AgentTracer::activeSpan);
    if (span == null) {
      return delegate;
    }

    try (final AgentScope scope = activateSpan(span, false)) {
      return new TracingSubscriber<>(delegate, context, activeScope());
    }
  }

  public static class TracingSubscriber<T>
      implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

    private final Subscriber<T> delegate;
    private final Context context;
    private final TraceScope parentScope;
    private final AtomicReference<TraceScope.Continuation> continuation = new AtomicReference<>();
    private Subscription subscription;

    public TracingSubscriber(
        final Subscriber<T> delegate, final Context context, final TraceScope parentScope) {
      this.delegate = delegate;
      this.context = context;
      this.parentScope = parentScope;
      parentScope.setAsyncPropagation(true);
      continuation.set(parentScope.capture());
      if (context != null) {
        context.put(TraceScope.class, parentScope);
      }
    }

    @Override
    public Context currentContext() {
      return context;
    }

    @Override
    public void onSubscribe(final Subscription subscription) {
      this.subscription = subscription;

      final TraceScope.Continuation continuation =
          this.continuation.getAndSet(parentScope.capture());
      try (final TraceScope scope = continuation.activate()) {
        delegate.onSubscribe(this);
      }
    }

    @Override
    public Object scanUnsafe(final Attr attr) {
      if (attr == Attr.PARENT) {
        return subscription;
      } else {
        if (attr == Attr.ACTUAL) {
          return delegate;
        } else {
          return null;
        }
      }
    }

    @Override
    public void request(final long n) {
      final TraceScope.Continuation continuation =
          this.continuation.getAndSet(parentScope.capture());
      try (final TraceScope scope = continuation.activate()) {
        subscription.request(n);
      }
    }

    @Override
    public void cancel() {
      final TraceScope.Continuation continuation = this.continuation.getAndSet(null);
      try (final TraceScope scope = continuation.activate()) {
        subscription.cancel();
      }
    }

    @Override
    public void onNext(final T t) {
      final TraceScope.Continuation continuation =
          this.continuation.getAndSet(parentScope.capture());
      try (final TraceScope scope = continuation.activate()) {
        delegate.onNext(t);
      }
    }

    @Override
    public void onError(final Throwable t) {
      final TraceScope.Continuation continuation = this.continuation.getAndSet(null);
      try (final TraceScope scope = continuation.activate()) {
        delegate.onError(t);
        activeSpan().setError(true);
        activeSpan().addThrowable(t);
      }
    }

    @Override
    public void onComplete() {
      final TraceScope.Continuation continuation = this.continuation.getAndSet(null);
      try (final TraceScope scope = continuation.activate()) {
        delegate.onComplete();
      }
    }

    @Override
    public int requestFusion(final int requestedMode) {
      return Fuseable.NONE;
    }

    @Override
    public T poll() {
      return null;
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public void clear() {}
  }
}
