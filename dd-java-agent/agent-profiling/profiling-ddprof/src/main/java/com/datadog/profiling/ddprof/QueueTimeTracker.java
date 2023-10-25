package com.datadog.profiling.ddprof;

import com.datadoghq.profiler.JavaProfiler;
import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper;
import java.lang.ref.WeakReference;

public class QueueTimeTracker implements QueueTiming {

  private final JavaProfiler profiler;
  private final Thread origin;
  private final long threshold;
  private final long startTicks;
  private WeakReference<Object> weakTask;
  private Class<?> scheduler;

  public QueueTimeTracker(JavaProfiler profiler, long threshold) {
    this.profiler = profiler;
    this.origin = Thread.currentThread();
    this.threshold = threshold;
    // TODO get this from JFR if available instead of making a JNI call
    this.startTicks = profiler.getCurrentTicks();
  }

  @Override
  public void setTask(Object task) {
    this.weakTask = new WeakReference<>(task);
  }

  @Override
  public void setScheduler(Class<?> scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public void close() {
    assert weakTask != null && scheduler != null;
    Object task = this.weakTask.get();
    if (task != null) {
      // potentially avoidable JNI call
      long endTicks = profiler.getCurrentTicks();
      if (profiler.isThresholdExceeded(threshold, startTicks, endTicks)) {
        // note: because this type traversal can update secondary_super_cache (see JDK-8180450)
        // we avoid doing this unless we are absolutely certain we will record the event
        Class<?> taskType = TaskWrapper.getUnwrappedType(task);
        if (taskType != null) {
          profiler.recordQueueTime(startTicks, endTicks, taskType, scheduler, origin);
        }
      }
    }
  }
}
