package com.datadog.profiling.ddprof;

import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.bootstrap.instrumentation.api.AsyncProfiledTaskHandoff;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import java.lang.ref.WeakReference;

public class QueueTimeTracker implements QueueTiming {

  private final DatadogProfiler profiler;
  private final Thread origin;
  private final long startTicks;
  private final long startMillis;
  private final long submittingSpanId;
  private WeakReference<Object> weakTask;
  // FIXME this can be eliminated by altering the instrumentation
  //  since it is known when the item is polled from the queue
  private Class<?> scheduler;
  private Class<?> queue;
  private int queueLength;

  public QueueTimeTracker(DatadogProfiler profiler, long startTicks, long submittingSpanId) {
    this.profiler = profiler;
    this.origin = Thread.currentThread();
    this.startTicks = startTicks;
    this.startMillis = System.currentTimeMillis();
    this.submittingSpanId = submittingSpanId;
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
  public void setQueue(Class<?> queue) {
    this.queue = queue;
  }

  @Override
  public void setQueueLength(int queueLength) {
    this.queueLength = queueLength;
  }

  @Override
  public void report() {
    assert weakTask != null && scheduler != null;
    Object task = this.weakTask.get();
    if (task != null) {
      long consumingSpanIdOverride = 0L;
      if (isExecutorWrapperTask(task)) {
        AgentSpan span = AgentTracer.activeSpan();
        if (span != null && span.context() instanceof ProfilerContext) {
          ProfilerContext pc = (ProfilerContext) span.context();
          if (pc.getSpanId() == submittingSpanId) {
            long startNano = System.nanoTime();
            AsyncProfiledTaskHandoff.setPendingActivationStartNano(startNano);
            consumingSpanIdOverride = pc.getSyntheticWorkSpanIdForActivation(startNano);
          }
        }
      }
      // indirection reduces shallow size of the tracker instance
      profiler.recordQueueTimeEvent(
          startTicks,
          task,
          scheduler,
          queue,
          queueLength,
          origin,
          submittingSpanId,
          consumingSpanIdOverride);
    }
  }

  @Override
  public boolean sample() {
    return profiler.shouldRecordQueueTimeEvent(startMillis);
  }

  /**
   * True for {@code Wrapper} / {@code ComparableRunnable} (agent-bootstrap) so we only align
   * {@link AsyncProfiledTaskHandoff} with tasks that use the same execution path; avoids a
   * compile dependency on agent-bootstrap.
   */
  private static boolean isExecutorWrapperTask(Object task) {
    if (task == null) {
      return false;
    }
    String n = task.getClass().getName();
    return n.equals("datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper")
        || n.equals("datadog.trace.bootstrap.instrumentation.java.concurrent.ComparableRunnable");
  }
}
