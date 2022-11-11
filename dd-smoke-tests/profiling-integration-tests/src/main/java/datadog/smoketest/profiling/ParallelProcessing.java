package datadog.smoketest.profiling;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.math3.distribution.NormalDistribution;

final class ParallelProcessing {
  void run(int workers, long meanServiceTimeNs, int runTimeSecs, Consumer<Long> workTask) {
    Thread[] threads = new Thread[workers];
    for (int i = 0; i < workers; i++) {
      threads[i] =
          new Thread(
              () -> {
                long endTs = System.nanoTime() + TimeUnit.SECONDS.toNanos(runTimeSecs);

                NormalDistribution distribution =
                    new NormalDistribution(meanServiceTimeNs, meanServiceTimeNs / 1000d);
                while (!Thread.currentThread().isInterrupted() && System.nanoTime() < endTs) {
                  workTask.accept((long) distribution.sample());
                }
              },
              "Worker " + i);
      threads[i].setDaemon(true);
      threads[i].start();
    }
    for (Thread thread : threads) {
      try {
        thread.join();
      } catch (InterruptedException ignored) {
      }
    }
  }
}
