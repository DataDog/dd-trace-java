package datadog.http.client.okhttp;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** {@link ExecutorService} that always rejects requests. */
public final class RejectingExecutorService extends AbstractExecutorService {
  public static final RejectingExecutorService INSTANCE = new RejectingExecutorService();

  @Override
  public void execute(final Runnable command) {
    throw new RejectedExecutionException("Unexpected request to execute async task");
  }

  @Override
  public void shutdown() {}

  @Override
  public List<Runnable> shutdownNow() {
    return Collections.emptyList();
  }

  @Override
  public boolean isShutdown() {
    return true;
  }

  @Override
  public boolean isTerminated() {
    return true;
  }

  @Override
  public boolean awaitTermination(final long timeout, final TimeUnit unit) {
    return true;
  }
}
