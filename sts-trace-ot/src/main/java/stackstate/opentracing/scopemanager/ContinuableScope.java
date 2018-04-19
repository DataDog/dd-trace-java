package stackstate.opentracing.scopemanager;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.noop.NoopScopeManager;
import java.io.Closeable;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import stackstate.opentracing.DDSpanContext;
import stackstate.opentracing.PendingTrace;
import stackstate.trace.context.TraceScope;

@Slf4j
public class ContinuableScope implements Scope, TraceScope {
  final ContextualScopeManager scopeManager;
  final AtomicInteger refCount;
  private final Span wrapped;
  private final boolean finishOnClose;
  private final Scope toRestore;

  ContinuableScope(
      final ContextualScopeManager scopeManager, final Span wrapped, final boolean finishOnClose) {
    this(scopeManager, new AtomicInteger(1), wrapped, finishOnClose);
  }

  private ContinuableScope(
      final ContextualScopeManager scopeManager,
      final AtomicInteger refCount,
      final Span wrapped,
      final boolean finishOnClose) {

    this.scopeManager = scopeManager;
    this.refCount = refCount;
    this.wrapped = wrapped;
    this.finishOnClose = finishOnClose;
    this.toRestore = scopeManager.tlsScope.get();
    scopeManager.tlsScope.set(this);
  }

  @Override
  public void close() {
    if (scopeManager.tlsScope.get() != this) {
      return;
    }

    if (refCount.decrementAndGet() == 0 && finishOnClose) {
      wrapped.finish();
    }

    scopeManager.tlsScope.set(toRestore);
  }

  @Override
  public Span span() {
    return wrapped;
  }

  /**
   * The continuation returned should be closed after the associa
   *
   * @param finishOnClose
   * @return
   */
  public Continuation capture(final boolean finishOnClose) {
    return new Continuation(this.finishOnClose && finishOnClose);
  }

  public class Continuation implements Closeable, TraceScope.Continuation {
    public WeakReference<Continuation> ref;

    private final AtomicBoolean used = new AtomicBoolean(false);
    private final PendingTrace trace;
    private final boolean finishSpanOnClose;

    private Continuation(final boolean finishOnClose) {
      this.finishSpanOnClose = finishOnClose;
      refCount.incrementAndGet();
      if (wrapped.context() instanceof DDSpanContext) {
        final DDSpanContext context = (DDSpanContext) wrapped.context();
        trace = context.getTrace();
        trace.registerContinuation(this);
      } else {
        trace = null;
      }
    }

    public ClosingScope activate() {
      if (used.compareAndSet(false, true)) {
        for (final ScopeContext context : scopeManager.scopeContexts) {
          if (context.inContext()) {
            return new ClosingScope(context.activate(wrapped, finishSpanOnClose));
          }
        }
        return new ClosingScope(
            new ContinuableScope(scopeManager, refCount, wrapped, finishSpanOnClose));
      } else {
        log.debug("Reusing a continuation not allowed.  Returning no-op scope.");
        return new ClosingScope(NoopScopeManager.NoopScope.INSTANCE);
      }
    }

    @Override
    public void close() {
      used.getAndSet(true);
      if (trace != null) {
        trace.cancelContinuation(this);
      }
    }

    private class ClosingScope implements Scope, TraceScope {
      private final Scope wrappedScope;

      private ClosingScope(final Scope wrappedScope) {
        this.wrappedScope = wrappedScope;
      }

      @Override
      public Continuation capture(boolean finishOnClose) {
        if (wrappedScope instanceof TraceScope) {
          return ((TraceScope) wrappedScope).capture(finishOnClose);
        } else {
          log.debug(
              "{} Failed to capture. ClosingScope does not wrap a TraceScope: {}.",
              this,
              wrappedScope);
          return null;
        }
      }

      @Override
      public void close() {
        wrappedScope.close();
        ContinuableScope.Continuation.this.close();
      }

      @Override
      public Span span() {
        return wrappedScope.span();
      }
    }
  }
}
