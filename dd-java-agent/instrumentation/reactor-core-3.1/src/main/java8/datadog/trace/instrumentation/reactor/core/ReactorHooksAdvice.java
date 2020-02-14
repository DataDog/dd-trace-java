package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopTraceScope;
import datadog.trace.context.TraceScope;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

public class ReactorHooksAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void postInit() {
    Hooks.onEachOperator(ReactorHooksAdvice.class.getName(), tracingOperator());
  }

  public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> tracingOperator() {
    return Operators.lift(
        (scannable) -> {
          // Don't wrap ourselves, and ConnectableFlux causes an exception in early reactor versions
          // due to not having the correct super types for being handled by the LiftFunction
          // operator, Fuseable.ScalarCallable causes errors to break on newer versions of reactor
          if (scannable instanceof TracingSubscriber) {
            return false;
          } else if (scannable instanceof Fuseable.ScalarCallable) {
            return false;
          } else if (scannable instanceof ConnectableFlux) {
            return false;
          }
          // In reactor 3.1 some built in types are not Scannable, the object before we receive it
          // is sent through Scannable.from(). When this is done if the object is not a Scannable
          // then we will get one of 2 constant Scannables which are members of Scannable.Attr
          if (scannable.getClass().getName().startsWith("reactor.core.Scannable$Attr$")) {
            return false;
          }
          return true;
        },
        ReactorHooksAdvice::tracingSubscriber);
  }

  public static <T> CoreSubscriber<? super T> tracingSubscriber(
      final Scannable scannable, final CoreSubscriber<T> delegate) {
    if (delegate instanceof DirectProcessor) {
      return delegate;
    }
    if (delegate instanceof Fuseable.ScalarCallable) {
      return delegate;
    }

    Context context = delegate.currentContext();
    final Optional<AgentSpan> maybeSpan = context.getOrEmpty(AgentSpan.class);
    final AgentSpan span = maybeSpan.orElseGet(AgentTracer::activeSpan);
    if (span == null) {
      return delegate;
    }

    try (final AgentScope scope = activateSpan(span, false)) {
      if (context.getOrDefault(AgentSpan.class, null) != span) {
        context = context.put(AgentSpan.class, span);
      }
      return new TracingSubscriber<>(delegate, context, activeScope());
    }
  }

  @Slf4j
  public static class TracingSubscriber<T>
      implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<TraceScope.Continuation> continuation = new AtomicReference<>();

    private final Subscriber<T> delegate;
    private final Context context;
    private final TraceScope parentScope;
    private Subscription subscription;

    public TracingSubscriber(
        final Subscriber<T> delegate, final Context context, final TraceScope parentScope) {
      this.delegate = delegate;
      this.context = context;
      this.parentScope = parentScope;

      parentScope.setAsyncPropagation(true);
      continuation.set(parentScope.capture());
    }

    private TraceScope maybeScope() {
      if (active.get()) {
        final TraceScope.Continuation continuation =
            this.continuation.getAndSet(parentScope.capture());
        return continuation.activate();
      } else {
        return NoopTraceScope.INSTANCE;
      }
    }

    private TraceScope maybeScopeAndDeactivate() {
      if (active.getAndSet(false)) {
        final TraceScope.Continuation continuation = this.continuation.getAndSet(null);
        return continuation.activate();
      } else {
        return NoopTraceScope.INSTANCE;
      }
    }

    /*
     * Methods from CoreSubscriber
     */

    @Override
    public Context currentContext() {
      return context;
    }

    @Override
    public void onSubscribe(final Subscription subscription) {
      this.subscription = subscription;

      try (final TraceScope scope = maybeScope()) {
        delegate.onSubscribe(this);
      }
    }

    @Override
    public void onNext(final T t) {
      try (final TraceScope scope = maybeScope()) {
        delegate.onNext(t);
      }
    }

    @Override
    public void onError(final Throwable t) {
      try (final TraceScope scope = maybeScopeAndDeactivate()) {
        delegate.onError(t);
        activeSpan().setError(true);
        activeSpan().addThrowable(t);
      }
    }

    @Override
    public void onComplete() {
      try (final TraceScope scope = maybeScopeAndDeactivate()) {
        delegate.onComplete();
      }
    }

    /*
     * Methods from Subscription
     */

    @Override
    public void request(final long n) {
      try (final TraceScope scope = maybeScope()) {
        subscription.request(n);
      }
    }

    @Override
    public void cancel() {
      try (final TraceScope scope = maybeScopeAndDeactivate()) {
        subscription.cancel();
      }
    }

    /*
     * Methods from Scannable
     */

    @Override
    public Object scanUnsafe(final Attr attr) {
      if (attr == Attr.PARENT) {
        return subscription;
      }
      if (attr == Attr.ACTUAL) {
        return delegate;
      }
      return null;
    }

    /*
     * Methods from Fuseable.QueueSubscription
     */

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
      return false;
    }

    @Override
    public void clear() {}
  }
}
