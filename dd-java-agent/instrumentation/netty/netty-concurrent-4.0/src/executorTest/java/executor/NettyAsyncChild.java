package executor;

import datadog.trace.api.Trace;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NettyAsyncChild implements Runnable, Callable<Void> {
  private final AtomicBoolean blocked;
  private final boolean traced;

  public NettyAsyncChild() {
    this(true, false);
  }

  public NettyAsyncChild(boolean traced, boolean blocked) {
    this.traced = traced;
    this.blocked = new AtomicBoolean(blocked);
  }

  public void unblock() {
    blocked.set(false);
  }

  @Override
  public void run() {
    while (blocked.get()) {
      // Hold the worker until the cancellation test releases it.
    }
    if (traced) {
      asyncChild();
    }
  }

  @Override
  public Void call() {
    run();
    return null;
  }

  @Trace(operationName = "asyncChild")
  private void asyncChild() {}
}
