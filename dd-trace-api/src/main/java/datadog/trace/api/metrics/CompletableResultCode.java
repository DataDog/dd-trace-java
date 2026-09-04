package datadog.trace.api.metrics;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@SuppressFBWarnings(
    value = "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR",
    justification = "Not a singleton")
public final class CompletableResultCode {
  private static final CompletableResultCode SUCCESS = new CompletableResultCode(true);
  private static final CompletableResultCode FAILURE = new CompletableResultCode(false);

  private final SharedState sharedState;
  private final boolean resultView;

  private Boolean resultViewSuccess;
  private List<Runnable> callbacks;

  public CompletableResultCode() {
    this(new SharedState(), false);
  }

  private CompletableResultCode(SharedState sharedState, boolean resultView) {
    this.sharedState = sharedState;
    this.resultView = resultView;
  }

  private CompletableResultCode(boolean success) {
    this();
    sharedState.success = success;
  }

  public static CompletableResultCode ofSuccess() {
    return SUCCESS;
  }

  public static CompletableResultCode ofFailure() {
    return FAILURE;
  }

  public CompletableResultCode newResultView() {
    return new CompletableResultCode(sharedState, true);
  }

  public CompletableResultCode succeed() {
    return complete(true);
  }

  public CompletableResultCode fail() {
    return complete(false);
  }

  public boolean isSuccess() {
    synchronized (sharedState) {
      return Boolean.TRUE.equals(outcome());
    }
  }

  public boolean isDone() {
    synchronized (sharedState) {
      return outcome() != null;
    }
  }

  /**
   * Registers an action to run on the completing thread. If this result is already complete, the
   * action runs immediately on the calling thread.
   *
   * @param callback action to run after completion
   * @return this result
   * @throws NullPointerException if {@code callback} is {@code null}
   */
  public CompletableResultCode whenComplete(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    synchronized (sharedState) {
      if (outcome() == null) {
        if (callbacks == null) {
          callbacks = new ArrayList<>();
          if (sharedState.callbackResults == null) {
            sharedState.callbackResults = new ArrayList<>();
          }
          sharedState.callbackResults.add(this);
        }
        callbacks.add(callback);
        return this;
      }
    }
    callback.run();
    return this;
  }

  /**
   * Waits up to the timeout for completion and returns this result. A timeout does not complete or
   * cancel the operation; use {@link #isDone()} and {@link #isSuccess()} to inspect the outcome.
   *
   * @param timeout maximum time to wait
   * @param unit unit of the timeout
   * @return this result, which may still be incomplete after the timeout
   */
  public CompletableResultCode join(long timeout, TimeUnit unit) {
    synchronized (sharedState) {
      if (outcome() != null) {
        return this;
      }

      long remainingNanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
      while (outcome() == null && remainingNanos > 0) {
        long start = System.nanoTime();
        try {
          NANOSECONDS.timedWait(sharedState, remainingNanos);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
          break;
        }
        remainingNanos -= Math.max(1, System.nanoTime() - start);
      }
    }
    return this;
  }

  private CompletableResultCode complete(boolean succeeded) {
    List<Runnable> completionCallbacks;
    synchronized (sharedState) {
      if (outcome() != null) {
        return this;
      }

      if (resultView) {
        resultViewSuccess = succeeded;
        completionCallbacks = callbacks;
        callbacks = null;
        removeCallbackResult();
      } else {
        sharedState.success = succeeded;
        completionCallbacks = collectCallbacks();
      }
      sharedState.notifyAll();
    }

    Throwable firstFailure = null;
    if (completionCallbacks != null) {
      for (Runnable callback : completionCallbacks) {
        try {
          callback.run();
        } catch (RuntimeException | Error failure) {
          if (firstFailure == null) {
            firstFailure = failure;
          }
        }
      }
    }
    if (firstFailure instanceof RuntimeException) {
      throw (RuntimeException) firstFailure;
    }
    if (firstFailure != null) {
      throw (Error) firstFailure;
    }
    return this;
  }

  private Boolean outcome() {
    return resultView && resultViewSuccess != null ? resultViewSuccess : sharedState.success;
  }

  private List<Runnable> collectCallbacks() {
    if (sharedState.callbackResults == null) {
      return null;
    }
    List<Runnable> completionCallbacks = new ArrayList<>();
    for (CompletableResultCode result : sharedState.callbackResults) {
      completionCallbacks.addAll(result.callbacks);
      result.callbacks = null;
    }
    sharedState.callbackResults = null;
    return completionCallbacks;
  }

  private void removeCallbackResult() {
    if (sharedState.callbackResults != null) {
      sharedState.callbackResults.remove(this);
      if (sharedState.callbackResults.isEmpty()) {
        sharedState.callbackResults = null;
      }
    }
  }

  private static final class SharedState {
    private Boolean success;
    private List<CompletableResultCode> callbackResults;
  }
}
