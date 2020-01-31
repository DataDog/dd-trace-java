package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.AgentTracer;
import java.util.Optional;
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
    return Operators.lift((s, sub) -> tracingSubscriber(sub));
  }

  public static <T> CoreSubscriber<? super T> tracingSubscriber(final CoreSubscriber<T> delegate) {
    final Context context = delegate.currentContext();
    final Optional<AgentSpan> maybeSpan = context.getOrEmpty(AgentSpan.class);
    final AgentSpan span = maybeSpan.orElseGet(AgentTracer::activeSpan);
    if (span == null) {
      return delegate;
    }

    try (final AgentScope scope = activateSpan(span, false)) {
      return new TracingSubscriber<>(delegate, context, scope);
    }
  }

  public static class TracingSubscriber<T>
      implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

    private final Subscriber<T> delegate;
    private final Context context;
    private final AgentScope parentScope;
    private Subscription subscription;

    public TracingSubscriber(
        final Subscriber<T> delegate, final Context context, final AgentScope parentScope) {
      this.delegate = delegate;
      this.context = context;
      this.parentScope = parentScope;
    }

    @Override
    public Context currentContext() {
      return context;
    }

    @Override
    public void onSubscribe(final Subscription s) {
      subscription = s;
      try (final AgentScope scope = activateSpan(parentScope.span(), false)) {
        delegate.onSubscribe(this);
      }
    }

    @Override
    public Object scanUnsafe(final Attr key) {
      if (key == Attr.PARENT) {
        return subscription;
      } else {
        if (key == Attr.ACTUAL) {
          return delegate;
        } else {
          return null;
        }
      }
    }

    @Override
    public void request(final long n) {
      try (final AgentScope scope = activateSpan(parentScope.span(), false)) {
        subscription.request(n);
      }
    }

    @Override
    public void cancel() {
      try (final AgentScope scope = activateSpan(parentScope.span(), false)) {
        subscription.cancel();
      }
    }

    @Override
    public void onNext(final T t) {
      try (final AgentScope scope = activateSpan(parentScope.span(), false)) {
        delegate.onNext(t);
      }
    }

    @Override
    public void onError(final Throwable t) {
      try (final AgentScope scope = activateSpan(parentScope.span(), false)) {
        delegate.onError(t);
        scope.span().addThrowable(t);
      }
    }

    @Override
    public void onComplete() {
      try (final AgentScope scope = activateSpan(parentScope.span(), false)) {
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
