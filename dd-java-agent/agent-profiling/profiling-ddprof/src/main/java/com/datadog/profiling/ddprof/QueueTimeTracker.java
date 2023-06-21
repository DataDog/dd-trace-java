package com.datadog.profiling.ddprof;

import com.datadoghq.profiler.JavaProfiler;
import datadog.trace.api.profiling.QueueTiming;

public class QueueTimeTracker implements QueueTiming {

  private final JavaProfiler profiler;
  private final Thread origin;
  private final long threshold;
  private final long startMillis;
  private final long startTicks;
  private final long localRootSpanId;
  private final long spanId;
  private Class<?> task;
  private Class<?> scheduler;

  public QueueTimeTracker(
      JavaProfiler profiler, long threshold, long localRootSpanId, long spanId) {
    this.profiler = profiler;
    this.origin = Thread.currentThread();
    this.threshold = threshold;
    this.startMillis = System.currentTimeMillis();
    // TODO get this from JFR if available instead of making a JNI call
    this.startTicks = profiler.getCurrentTicks();
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
  }

  @Override
  public void setTask(Class<?> task) {
    this.task = task;
  }

  @Override
  public void setScheduler(Class<?> scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public void close() {
    long endMillis = System.currentTimeMillis();
    if (endMillis - startMillis >= threshold) {
      assert task != null && scheduler != null;
      profiler.recordQueueTime(
          localRootSpanId, spanId, startTicks, profiler.getCurrentTicks(), task, scheduler, origin);
    }
  }
}
