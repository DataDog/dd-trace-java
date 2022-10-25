package datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.math3.distribution.ExponentialDistribution;

public final class CodeHotspotsApplication {
  public static void main(String[] args) {
    CodeHotspotsApplication application = new CodeHotspotsApplication(GlobalTracer.get());
    String kind = args[0];

    switch (kind) {
      case "reactive":
        application.queueing(
            Integer.parseInt(args[1]),
            Long.parseLong(args[2]),
            Long.parseLong(args[3]),
            Integer.parseInt(args[4]));
        break;
      case "batch":
        application.batch(Long.parseLong(args[1]), Integer.parseInt(args[2]));
        break;
      case "fanout":
        application.fanout(
            Integer.parseInt(args[1]), Long.parseLong(args[2]), Integer.parseInt(args[3]));
        break;
      default:
        throw new RuntimeException("Invalid application kind: " + kind);
    }
  }

  private final Tracer tracer;

  private CodeHotspotsApplication(Tracer tracer) {
    this.tracer = tracer;
  }

  private void batch(long meanServiceTimeNs, int runtimeSecs) {
    ParallelProcessing processor = new ParallelProcessing();
    Span topSpan = tracer.buildSpan("top").start();

    try (Scope scope = tracer.activateSpan(topSpan)) {
      processor.run(1, meanServiceTimeNs, runtimeSecs, this::process);
    }
  }

  private void fanout(int workers, long meanServiceTimeNs, int runtimeSecs) {
    ParallelProcessing processor = new ParallelProcessing();
    Span topSpan = tracer.buildSpan("top").start();

    try (Scope scope = tracer.activateSpan(topSpan)) {
      processor.run(workers, meanServiceTimeNs, runtimeSecs, this::process);
    }
  }

  private void queueing(int workers, long meanServiceTimeNs, long arrivalRate, int runtimeSecs) {
    long finalTs = System.nanoTime() + TimeUnit.SECONDS.toNanos(runtimeSecs);

    ExponentialDistribution arrivalDistribution =
        new ExponentialDistribution(1_000_000_000d / arrivalRate);
    QueueProcessing processing = new QueueProcessing(workers, workers, meanServiceTimeNs);

    Span topSpan = tracer.buildSpan("top").start();

    try {
      while (!Thread.currentThread().isInterrupted() && System.nanoTime() < finalTs) {
        long sample = (long) arrivalDistribution.sample();
        long nextArrivalTs = System.nanoTime() + sample;
        while (System.nanoTime() < nextArrivalTs) {
          LockSupport.parkNanos(nextArrivalTs - System.nanoTime());
          if (Thread.currentThread().isInterrupted()) {
            return;
          }
        }
        try (Scope scope = tracer.activateSpan(topSpan)) {
          processing.enqueue(this::process);
        }
      }
    } finally {
      processing.shutdown();
      topSpan.finish();
    }
  }

  private long process(long serviceTime) {
    long cntr = 0;
    long endTs = System.nanoTime() + serviceTime;
    Span span = tracer.buildSpan("work_item").start();
    try (Scope scope = tracer.activateSpan(span)) {
      while (!Thread.currentThread().isInterrupted() && System.nanoTime() < endTs) {
        cntr++;
      }
    } finally {
      span.finish();
    }
    return cntr;
  }
}
