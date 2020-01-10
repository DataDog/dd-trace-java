package datadog.trace.instrumentation.finagle;

import static datadog.trace.bootstrap.WeakMap.Provider.newWeakMap;
import static datadog.trace.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;

import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;
import com.twitter.util.Promise;
import com.twitter.util.Try;
import datadog.trace.bootstrap.WeakMap;
import datadog.trace.context.TraceScope;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TwitterPromiseUtils {
  private static final WeakMap.ValueSupplier<PromiseChain> CHAIN_SUPPLIER =
      new PromiseChainSupplier();

  private static final WeakMap<Promise, PromiseChain> PROMISE_TO_CHAIN = newWeakMap();
  private static final WeakMap<Try, PromiseChain> TRY_TO_CHAIN = newWeakMap();

  public static void linkTryToContinuation(final Try tryInstance, final Promise promise) {
    final PromiseChain chain = PROMISE_TO_CHAIN.get(promise);

    if (chain != null) {
      TRY_TO_CHAIN.put(tryInstance, chain);

      System.out.println("Try GUYS: " + promise);
    }
  }

  public static void copyContinuation(final Promise oldPromise, final Promise newPromise) {
    final PromiseChain chain = PROMISE_TO_CHAIN.get(oldPromise);

    if (chain != null) {
      System.out.println(
          "Actually added to chain " + oldPromise.hashCode() + " - " + newPromise.hashCode());
      chain.addPromiseToTop(newPromise);

      PROMISE_TO_CHAIN.put(newPromise, chain);
    }
  }

  public static void activateContinuation(final Try tryInstance) {
    final PromiseChain chain = TRY_TO_CHAIN.get(tryInstance);

    if (chain != null) {
      chain.activateContinuation();
    }
  }

  public static void finishPromiseForTry(final Try tryInstance) {
    final PromiseChain chain = TRY_TO_CHAIN.get(tryInstance);

    if (chain != null) {
      chain.finishPromise();
    }
  }

  /**
   * Adds a listener to a future and sets up context propagation for promises. Should be called
   * before the Promise is returned to client code. Continuation should only be passed in from the
   * top level promise
   */
  public static <T> void setupScopePropagation(
      final Future<T> future, final FutureEventListener<T> listener) {

    if (future instanceof Promise) {
      System.out.println("Promise instance: " + future);
      future.addEventListener(listener);

      final PromiseChain chain = PROMISE_TO_CHAIN.getOrCreate((Promise) future, CHAIN_SUPPLIER);
      chain.addPromise((Promise) future);

      if (chain.isTopOfChain((Promise) future)) {
        System.out.println("Setting continuation on chain");
        chain.continuation = propagate().capture();

        if (future.isDefined()) {
          System.out.println("Inside is defined");
          // If the promise finishes before we put the continuation into the chain,
          // then the WaitQueue instrumentation would not have seen it.  The continuation needs to
          // be closed here.
          // In the race where the promise finishes after setting the continuation but before the
          // `.isDefined()` call then close() is called twice: a no-op
          // Additionally, any listeners/transformers added to the promise after it is finished will
          // be executed when it is added instead of on a separate executor: thus the continuation
          // is not needed

          chain.continuation.close();
        }
      }
    } else {
      // Future is already finished just need to create a delegating listener that closes the scope
      future.addEventListener(new ListenerWrapper<>(listener, activeScope()));
    }
  }

  public static class ContinuationSupplier
      implements WeakMap.ValueSupplier<TraceScope.Continuation> {

    @Override
    public TraceScope.Continuation get() {
      return propagate().capture();
    }
  }

  public static class PromiseChain {
    private final AtomicInteger size = new AtomicInteger();
    private final WeakMap<Promise, Boolean> promises = newWeakMap();
    private TraceScope.Continuation continuation;
    private final AtomicBoolean activated = new AtomicBoolean(false);
    private TraceScope activatedScope;
    private WeakReference<Promise> topOfChain;

    public void addPromise(final Promise promise) {
      if (!promises.containsKey(promise)) {
        promises.put(promise, true);
        size.getAndIncrement();

        if (topOfChain == null || topOfChain.get() == null) {
          topOfChain = new WeakReference<>(promise);
        }
      }
    }

    public void addPromiseToTop(final Promise newPromise) {
      addPromise(newPromise);

      // Close any previous continuations belonging to the old top of chain
      if (topOfChain != null && topOfChain.get() != newPromise && continuation != null) {
        continuation.close();
        continuation = null;
      }
      topOfChain = new WeakReference<>(newPromise);
    }

    public void activateContinuation() {
      if (continuation == null) {
        return;
      }

      if (activated.compareAndSet(false, true)) {
        activatedScope = continuation.activate();
        System.out.println("Activated continuation");
        System.out.println(topOfChain.get());
      }
    }

    public void finishPromise() {
      if (size.decrementAndGet() == 0 && activatedScope != null) {
        System.out.println("Closing activated scope");
        activatedScope.close();
        activatedScope = null;
      }

      System.out.println("Size:  " + size.get());
    }

    public boolean isTopOfChain(final Promise future) {
      return topOfChain != null && topOfChain.get() == future;
    }
  }

  public static class PromiseChainSupplier implements WeakMap.ValueSupplier<PromiseChain> {
    @Override
    public PromiseChain get() {
      return new PromiseChain();
    }
  }

  public static class ListenerWrapper<T> implements FutureEventListener<T> {
    private final TraceScope scope;
    private final FutureEventListener<T> delegate;

    public ListenerWrapper(final FutureEventListener<T> delegate, final TraceScope scope) {
      this.delegate = delegate;
      this.scope = scope;
    }

    @Override
    public void onSuccess(final T value) {
      try {
        delegate.onSuccess(value);
      } finally {
        scope.close();
      }
    }

    @Override
    public void onFailure(final Throwable cause) {
      try {
        delegate.onFailure(cause);
      } finally {
        scope.close();
      }
    }
  }
}
