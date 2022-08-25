package datadog.trace.bootstrap.instrumentation.api8.java.concurrent;

import java.util.concurrent.CompletableFuture;

public class StatusSettingCompletableFuture<T, C> extends CompletableFuture<T> {
  public static <T, C> StatusSettingCompletableFuture<T, C> wrap(StatusSettable<C> settable) {
    return new StatusSettingCompletableFuture<>(settable);
  }

  private final StatusSettable<C> settable;

  public StatusSettingCompletableFuture(StatusSettable settable) {
    this.settable = settable;
  }

  @Override
  public boolean complete(T value) {
    C context = settable.statusStart();
    boolean success;
    try {
      success = super.complete(value);
      if (success) {
        settable.setSuccess(context);
      }
    } finally {
      settable.statusFinished(context);
    }
    return success;
  }

  @Override
  public boolean completeExceptionally(Throwable ex) {
    C context = settable.statusStart();
    boolean success;
    try {
      success = super.completeExceptionally(ex);
      if (success) {
        settable.setError(context, ex);
      }
    } finally {
      settable.statusFinished(context);
    }
    return success;
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    C context = settable.statusStart();
    boolean success;
    try {
      success = super.cancel(mayInterruptIfRunning);
      if (success) {
        settable.setSuccess(context);
      }
    } finally {
      settable.statusFinished(context);
    }
    return success;
  }
}
