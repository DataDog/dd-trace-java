package datadog.http.client.okhttp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class RejectingExecutorServiceTest {
  ExecutorService executorService = new RejectingExecutorService();

  @Test
  void testExecute() {
    assertThrows(RejectedExecutionException.class, () -> executorService.execute(() -> {}));
  }

  @Test
  void testOtherMethods() throws InterruptedException {
    assertTrue(executorService.isTerminated());
    assertTrue(executorService.awaitTermination(0, TimeUnit.SECONDS));
    assertTrue(executorService.isShutdown());
    assertDoesNotThrow(() -> executorService.shutdown());
    assertTrue(executorService.shutdownNow().isEmpty());
  }
}
