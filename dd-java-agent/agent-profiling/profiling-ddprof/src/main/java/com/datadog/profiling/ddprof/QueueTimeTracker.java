package com.datadog.profiling.ddprof;

import com.datadoghq.profiler.JavaProfiler;
import datadog.trace.api.profiling.QueueTiming;

public class QueueTimeTracker implements QueueTiming {

  private final JavaProfiler profiler;
  private final Thread origin;
  private final long threshold;
  private final long startTicks;
  private Class<?> task;
  private Class<?> scheduler;

  public QueueTimeTracker(JavaProfiler profiler, long threshold) {
    this.profiler = profiler;
    this.origin = Thread.currentThread();
    this.threshold = threshold;
    // TODO get this from JFR if available instead of making a JNI call
    this.startTicks = profiler.getCurrentTicks();
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
    assert task != null && scheduler != null;
    // potentually avoidable JNI call
    long endTicks = profiler.getCurrentTicks();
    if (profiler.isThresholdExceeded(threshold, startTicks, endTicks)) {
      profiler.recordQueueTime(startTicks, endTicks, task, scheduler, origin);
    }
  }
}
