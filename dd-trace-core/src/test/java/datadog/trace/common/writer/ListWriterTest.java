package datadog.trace.common.writer;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ListWriterTest {
  @Test
  void traceCountMatchesSizeAfterConcurrentWritesAndClears() throws Exception {
    ListWriter writer = new ListWriter();
    CyclicBarrier barrier = new CyclicBarrier(3);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    int rounds = 100;
    int operations = 100;
    try {
      Future<?> writes =
          executor.submit(
              () -> {
                for (int round = 0; round < rounds; round++) {
                  barrier.await(10, SECONDS);
                  for (int i = 0; i < operations; i++) {
                    writer.write(emptyList());
                  }
                  barrier.await(10, SECONDS);
                }
                return null;
              });
      Future<?> clears =
          executor.submit(
              () -> {
                for (int round = 0; round < rounds; round++) {
                  barrier.await(10, SECONDS);
                  for (int i = 0; i < operations; i++) {
                    writer.clear();
                  }
                  barrier.await(10, SECONDS);
                }
                return null;
              });

      for (int round = 0; round < rounds; round++) {
        writer.clear();
        barrier.await(10, SECONDS);
        barrier.await(10, SECONDS);
        // Both workers are quiescent until the next round starts.
        assertEquals(writer.size(), writer.getTraceCount(), "round " + round);
      }
      writes.get(10, SECONDS);
      clears.get(10, SECONDS);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, SECONDS));
    }
  }

  @Test
  void waitForTracesDoesNotReportClearedTraces() throws InterruptedException {
    ListWriter writer = new ListWriter();
    writer.write(emptyList());
    assertTrue(writer.waitForTracesMax(1, 0));

    writer.clear();

    assertEquals(0, writer.size());
    assertEquals(0, writer.getTraceCount());
    assertFalse(writer.waitForTracesMax(1, 1));
  }
}
