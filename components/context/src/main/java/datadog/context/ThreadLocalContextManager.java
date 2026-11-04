package datadog.context;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.annotation.Nullable;

/** {@link ContextManager} that uses a {@link ThreadLocal} to track context per thread. */
final class ThreadLocalContextManager implements ContextManager {
  static final ThreadLocalContextManager INSTANCE = new ThreadLocalContextManager();

  private static final ThreadLocal<ContextHolder> CONTEXT_HOLDER =
      ThreadLocal.withInitial(ContextHolder::new);

  private static final NoopContextContinuation ROOT_CONTINUATION =
      new NoopContextContinuation(Context.root());

  private static final byte SUPPRESS_CAPTURE = 1;

  private final Object listenersWriteLock = new Object();
  volatile ContextListener[] listeners = {};

  @Override
  public Context current() {
    return CONTEXT_HOLDER.get().current;
  }

  @Override
  public ContextScope attach(Context context) {
    return doAttach(context, null);
  }

  ContextScope doAttach(Context context, @Nullable ContextContinuationImpl continuation) {
    ContextHolder holder = CONTEXT_HOLDER.get();

    Context beforeAttach = holder.current;
    if (context == beforeAttach) {
      if (continuation != null) {
        // already attached, safe to release early to avoid resource leak
        continuation.releaseOnScopeClose();
        return continuation; // acts as no-op scope, avoiding allocation
      }
      return context.asScope(); // convert to scope without attaching
    }

    byte flags = holder.flags;
    holder.current = context;
    holder.flags = 0;
    notifyUpdate(listeners, beforeAttach, context);
    return continuation == null
        ? new ContextScopeImpl(context, beforeAttach, flags)
        : new ResumedScopeImpl(context, beforeAttach, flags, continuation);
  }

  @Override
  public ContextHandle swap(ContextHandle handle) {
    byte seedFlags = 0;
    Context context;
    if (handle instanceof Context) {
      context = (Context) handle;
    } else {
      seedFlags = ((ContextCheckpoint) handle).flags;
      context = ((ContextCheckpoint) handle).context;
    }

    ContextHolder holder = CONTEXT_HOLDER.get();
    Context beforeSwap = holder.current;
    byte flags = holder.flags;

    // Same-context skips resetting or restoring flags.
    if (context != beforeSwap) {
      holder.flags = seedFlags;
      holder.current = context;
      notifyUpdate(listeners, beforeSwap, context);
    }

    return checkpoint(beforeSwap, flags);
  }

  @Override
  public ContextHandle checkpoint(Context context) {
    return checkpoint(context, CONTEXT_HOLDER.get().flags);
  }

  private static ContextHandle checkpoint(Context context, byte flags) {
    return flags == 0 ? context : new ContextCheckpoint(context, flags);
  }

  @Override
  public ContextContinuation capture(Context context) {
    return context == Context.root() || !isCaptureEnabled()
        ? ROOT_CONTINUATION
        : new ContextContinuationImpl(context);
  }

  @Override
  public boolean isCaptureEnabled() {
    return (CONTEXT_HOLDER.get().flags & SUPPRESS_CAPTURE) == 0;
  }

  @Override
  public void setCaptureEnabled(boolean enabled) {
    ContextHolder holder = CONTEXT_HOLDER.get();
    if (enabled) {
      holder.flags &= ~SUPPRESS_CAPTURE;
    } else {
      holder.flags |= SUPPRESS_CAPTURE;
    }
  }

  @Override
  public void addListener(ContextListener listener) {
    synchronized (listenersWriteLock) {
      for (ContextListener l : listeners) {
        if (l == listener) {
          return;
        }
      }
      int oldLength = listeners.length;
      ContextListener[] update = Arrays.copyOf(listeners, oldLength + 1);
      update[oldLength] = listener;
      listeners = update;
    }
  }

  void clearListeners() {
    synchronized (listenersWriteLock) {
      listeners = new ContextListener[] {};
    }
  }

  static void notifyUpdate(ContextListener[] listeners, Context before, Context after) {
    for (ContextListener l : listeners) {
      try {
        l.onUpdate(before, after);
      } catch (Throwable ignore) {
      }
    }
  }

  static void notifyCapture(ContextListener[] listeners, Context context) {
    // only called for non-empty continuations
    for (ContextListener l : listeners) {
      try {
        l.onCapture(context);
      } catch (Throwable ignore) {
      }
    }
  }

  static void notifyRelease(ContextListener[] listeners, Context context) {
    // only called for non-empty continuations
    for (ContextListener l : listeners) {
      try {
        l.onRelease(context);
      } catch (Throwable ignore) {
      }
    }
  }

  private static class ContextScopeImpl implements ContextScope {
    private final Context context;
    private final Context beforeAttach;
    private final byte flags;

    private boolean closed;

    ContextScopeImpl(Context context, Context beforeAttach, byte flags) {
      this.context = context;
      this.beforeAttach = beforeAttach;
      this.flags = flags;
    }

    @Override
    public final Context context() {
      return context;
    }

    @Override
    public void close() {
      if (!closed) {
        ContextHolder holder = CONTEXT_HOLDER.get();
        // check for out-of-order close to avoid corrupting the current state
        if (context == holder.current) {
          holder.current = beforeAttach;
          holder.flags = flags;
          notifyUpdate(INSTANCE.listeners, context, beforeAttach);
          closed = true;
        }
      }
    }
  }

  private static final class ResumedScopeImpl extends ContextScopeImpl {
    @Nullable private ContextContinuationImpl continuation;

    ResumedScopeImpl(
        Context context,
        Context beforeAttach,
        byte flags,
        @Nullable ContextContinuationImpl continuation) {
      super(context, beforeAttach, flags);
      this.continuation = continuation;
    }

    @Override
    public void close() {
      if (continuation != null) {
        // release first to avoid resource leak, even on out-of-order close
        continuation.releaseOnScopeClose();
        continuation = null;
      }
      super.close(); // proceed to try and update the current execution unit
    }
  }

  private static final class ContextContinuationImpl implements ContextContinuation, ContextScope {
    private static final AtomicIntegerFieldUpdater<ContextContinuationImpl> COUNT =
        AtomicIntegerFieldUpdater.newUpdater(ContextContinuationImpl.class, "count");

    // these boundaries were selected to allow for speculative counting and fuzzy checks
    private static final int RELEASED = Integer.MIN_VALUE >> 1;
    private static final int HELD = (Integer.MAX_VALUE >> 1) + 1;

    private final Context context;

    /**
     * When positive this reflects the number of outstanding resumed scopes as well as whether there
     * is an active hold on the continuation:
     *
     * <table>
     * <tr><th>Value</th> <th>Meaning</th></tr>
     * <tr><td>0</td><td>Not held or resumed</td></tr>
     * <tr><td>1..HELD-1</td><td>Resumed, not held</td></tr>
     * <tr><td>HELD</td><td>Held, not yet resumed</td></tr>
     * <tr><td>HELD..MAX_INT</td><td>Resumed and held</td></tr>
     * </table>
     *
     * where HELD is at the mid-point between 1 and MAX_INT.
     *
     * <p>A negative value of RELEASED reflects that the continuation has either been resumed and
     * all associated scopes are now closed, or it has been explicitly released. This value was
     * chosen to be half the size of MIN_INT to avoid speculative additions in {@link #resume()}
     * from overflowing to a positive count.
     */
    private volatile int count = 0;

    ContextContinuationImpl(Context context) {
      this.context = context;
      notifyCapture(INSTANCE.listeners, context);
    }

    @Override
    public ContextContinuation hold() {
      // update initial count to record that this continuation has a hold
      COUNT.compareAndSet(this, 0, HELD);
      return this;
    }

    @Override
    public Context context() {
      return context;
    }

    @Override
    public ContextScope resume() {
      if (COUNT.incrementAndGet(this) > 0) {
        // speculative update succeeded, continuation can be resumed
        return INSTANCE.doAttach(context, this);
      } else {
        // continuation released or too many resumes; rollback count
        COUNT.decrementAndGet(this);
        return this; // acts as no-op scope, avoiding allocation
      }
    }

    @Override
    public void release() {
      int current = count;
      while (current >= HELD) {
        // remove the hold on this continuation by removing the offset
        COUNT.compareAndSet(this, current, current - HELD);
        current = count;
      }
      while (current == 0) {
        // no outstanding resumes and hold has been removed
        if (COUNT.compareAndSet(this, current, RELEASED)) {
          notifyRelease(INSTANCE.listeners, context);
          return;
        }
        current = count;
      }
    }

    void releaseOnScopeClose() {
      if (COUNT.compareAndSet(this, 1, RELEASED)) {
        // fast path: only one resume of the continuation (no hold)
        notifyRelease(INSTANCE.listeners, context);
      } else if (COUNT.decrementAndGet(this) == 0) {
        // slow path: multiple resumes, all scopes now closed (no hold)
        release();
      } /* else there are outstanding resumes or hold is in place */
    }

    @Override
    public void close() {}
  }

  private static final class ContextHolder {
    Context current = Context.root();
    byte flags; // execution-unit flags
  }
}
