package listener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ErrorHandlerObservation {
  private static final long TIMEOUT_SECONDS = 15L;

  private volatile Throwable blockingError;
  private volatile Throwable asyncError;

  private final CountDownLatch blockingErrorHandled = new CountDownLatch(1);
  private final CountDownLatch asyncErrorHandled = new CountDownLatch(1);

  public Throwable getBlockingError() {
    return blockingError;
  }

  public Throwable getAsyncError() {
    return asyncError;
  }

  public void recordBlockingErrorHandler(Throwable error) {
    blockingError = error;
    blockingErrorHandled.countDown();
  }

  public void recordAsyncErrorHandler(Throwable error) {
    asyncError = error;
    asyncErrorHandled.countDown();
  }

  public void awaitBlockingErrorHandler() {
    await(blockingErrorHandled, "blocking error handler");
  }

  public void awaitAsyncErrorHandler() {
    await(asyncErrorHandled, "async error handler");
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for " + description);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for " + description, e);
    }
  }
}
