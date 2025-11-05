package annotatedsample;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

public class GuavaTracedMethods {
  @WithSpan
  public static ListenableFuture<String> traceAsyncListenableFuture(
      ExecutorService executor, CountDownLatch latch) {
    TestFuture listenableFuture = TestFuture.ofComplete(latch, "hello");
    executor.submit(listenableFuture::start);
    return listenableFuture;
  }

  @WithSpan
  public static ListenableFuture<?> traceAsyncCancelledListenableFuture(CountDownLatch latch) {
    return TestFuture.ofComplete(latch, "hello");
  }

  @WithSpan
  public static ListenableFuture<?> traceAsyncFailingListenableFuture(
      ExecutorService executor, CountDownLatch latch, Throwable exception) {
    TestFuture listenableFuture = TestFuture.ofFailing(latch, exception);
    executor.submit(listenableFuture::start);
    return listenableFuture;
  }

  private static class TestFuture extends AbstractFuture<String> {
    private final CountDownLatch latch;
    private final String value;
    private final Throwable exception;

    private TestFuture(CountDownLatch latch, String value, Throwable exception) {
      this.latch = latch;
      this.value = value;
      this.exception = exception;
    }

    private void start() {
      try {
        if (!this.latch.await(5, SECONDS)) {
          throw new IllegalStateException("Latch still locked");
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      if (this.exception != null) {
        setException(this.exception);
      } else {
        set(this.value);
      }
    }

    private static TestFuture ofComplete(CountDownLatch latch, String value) {
      return new TestFuture(latch, value, null);
    }

    private static TestFuture ofFailing(CountDownLatch latch, Throwable exception) {
      return new TestFuture(latch, null, exception);
    }
  }
}
