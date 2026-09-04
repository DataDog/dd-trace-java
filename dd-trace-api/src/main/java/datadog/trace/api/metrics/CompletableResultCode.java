package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CompletableResultCode {
  private static final CompletableResultCode SUCCESS = new CompletableResultCode(true);
  private static final CompletableResultCode FAILURE = new CompletableResultCode(false);

  private Boolean success;
  private List<Runnable> callbacks;

  public CompletableResultCode() {}

  private CompletableResultCode(boolean success) {
    this.success = success;
  }

  public static CompletableResultCode ofSuccess() {
    return SUCCESS;
  }

  public static CompletableResultCode ofFailure() {
    return FAILURE;
  }

  public CompletableResultCode succeed() {
    return complete(true);
  }

  public CompletableResultCode fail() {
    return complete(false);
  }

  public synchronized boolean isSuccess() {
    return Boolean.TRUE.equals(success);
  }

  public synchronized boolean isDone() {
    return success != null;
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
    synchronized (this) {
      if (success == null) {
        if (callbacks == null) {
          callbacks = new ArrayList<>();
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
    if (isDone()) {
      return this;
    }
    CountDownLatch completed = new CountDownLatch(1);
    whenComplete(completed::countDown);
    try {
      completed.await(timeout, unit);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    return this;
  }

  private CompletableResultCode complete(boolean succeeded) {
    List<Runnable> completionCallbacks;
    synchronized (this) {
      if (success != null) {
        return this;
      }
      success = succeeded;
      completionCallbacks = callbacks;
      callbacks = null;
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
}
