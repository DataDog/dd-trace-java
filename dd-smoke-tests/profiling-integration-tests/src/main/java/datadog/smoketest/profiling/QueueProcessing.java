package datadog.smoketest.profiling;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.commons.math3.distribution.ExponentialDistribution;

final class QueueProcessing {
  private final BlockingQueue<Consumer<Long>> workQueue;
  private final ExecutorService executor;

  QueueProcessing(int queueCapacity, int workers, long meanServiceTimeNs) {
    workQueue = new ArrayBlockingQueue<>(queueCapacity);
    executor =
        Executors.newFixedThreadPool(
            workers,
            r -> {
              Thread t = new Thread(r, "Worker");
              t.setDaemon(true);
              return t;
            });
    for (int i = 0; i < workers; i++) {
      executor.submit(
          () -> {
            ExponentialDistribution distribution = new ExponentialDistribution(meanServiceTimeNs);
            while (!Thread.currentThread().isInterrupted()) {
              try {
                Consumer<Long> item = workQueue.take();
                long sample = (long) distribution.sample();
                System.out.println("=== service time: " + sample + "ns");
                item.accept(sample);
                System.out.println("=== submitted");
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            }
          });
    }
  }

  boolean enqueue(Consumer<Long> workItem) {
    return workQueue.offer(workItem);
  }

  void shutdown() {
    executor.shutdownNow();
  }
}
