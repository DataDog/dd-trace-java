package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopTraceScope;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.util.context.Context;

@Slf4j
public class TracingSubscriber<T>
    implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

  private final AtomicReference<TraceScope.Continuation> continuation = new AtomicReference<>();

  private final AgentSpan upstreamSpan;
  private final CoreSubscriber<T> delegate;
  private final Context context;
  private final AgentSpan downstreamSpan;
  private Subscription subscription;

  public TracingSubscriber(final AgentSpan upstreamSpan, final CoreSubscriber<T> delegate) {
    this.delegate = delegate;
    this.upstreamSpan = upstreamSpan;
    downstreamSpan =
        (AgentSpan)
            delegate.currentContext().getOrEmpty(AgentSpan.class).orElseGet(AgentTracer::noopSpan);

    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      final TraceScope downstreamScope = activeScope();
      if (downstreamScope != null) {
        downstreamScope.setAsyncPropagation(true);
        // create a hard reference to the trace that we don't want reported until we are done
        continuation.set(activeScope().capture());
      } else {
        continuation.set(AgentTracer.noopTraceScope().capture());
      }
    }

    // The context is exposed upstream so we put our upstream span here for use by the next
    // TracingSubscriber
    context = this.delegate.currentContext().put(AgentSpan.class, this.upstreamSpan);
  }

  @Override
  public Context currentContext() {
    return context;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    this.subscription = subscription;

    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      scope.setAsyncPropagation(true);
      delegate.onSubscribe(this);
    }
  }

  @Override
  public void onNext(final T t) {
    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      scope.setAsyncPropagation(true);
      delegate.onNext(t);
    }
  }

  private UnifiedScope finalScopeForDownstream() {
    if (continuation.get() != noopTraceScope().capture()) {
      // releases our reference to the trace
      final TraceScope scope = continuation.getAndSet(noopTraceScope().capture()).activate();
      scope.setAsyncPropagation(true);
      return new UnifiedScope(scope);
    } else {
      final AgentScope scope = activateSpan(downstreamSpan, false);
      scope.setAsyncPropagation(true);
      return new UnifiedScope(scope);
    }
  }

  @Override
  public void onError(final Throwable t) {
    try (final UnifiedScope scope = finalScopeForDownstream()) {
      delegate.onError(t);
    }
  }

  @Override
  public void onComplete() {
    try (final UnifiedScope scope = finalScopeForDownstream()) {
      delegate.onComplete();
    }
  }

  /*
   * Methods from Subscription
   */

  @Override
  public void request(final long n) {
    try (final AgentScope scope = activateSpan(upstreamSpan, false)) {
      scope.setAsyncPropagation(true);
      subscription.request(n);
    }
  }

  @Override
  public void cancel() {
    try (final AgentScope scope = activateSpan(upstreamSpan, false)) {
      scope.setAsyncPropagation(true);
      continuation.getAndSet(noopTraceScope().capture()).activate().close();
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

  public static class UnifiedScope implements AutoCloseable {
    private final Closeable scope;

    public UnifiedScope(final Closeable scope) {
      if (!(scope instanceof TraceScope) && !(scope instanceof AgentScope)) {
        throw new IllegalArgumentException("Should only be used for scopes");
      }
      this.scope = scope;
    }

    @SneakyThrows
    @Override
    public void close() {
      scope.close();
    }
  }
}
