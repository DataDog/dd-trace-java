package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AsyncProfiledTaskHandoff;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import java.util.concurrent.RunnableFuture;

public class Wrapper<T extends Runnable> implements Runnable, AutoCloseable {

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <T extends Runnable> Runnable wrap(T task) {
    if (task instanceof Wrapper
        || task instanceof RunnableFuture
        || task == null
        || exclude(RUNNABLE, task)) {
      return task;
    }
    AgentScope.Continuation continuation = captureActiveSpan();
    if (continuation != noopContinuation()) {
      // Capture TSC ticks at submission time so the queue-wait interval can be emitted
      // as a TaskBlock event when the task actually starts executing on the worker thread.
      // Returns 0 when the profiler is inactive; the guard in run() skips emission then.
      long submissionTicks = AgentTracer.get().getProfilingContext().getCurrentTicks();
      if (task instanceof Comparable) {
        return new ComparableRunnable(task, continuation, submissionTicks);
      }
      return new Wrapper<>(task, continuation, submissionTicks);
    }
    // don't wrap unless there is scope to propagate
    return task;
  }

  public static Runnable unwrap(Runnable task) {
    return task instanceof Wrapper ? ((Wrapper<?>) task).unwrap() : task;
  }

  protected final T delegate;
  private final AgentScope.Continuation continuation;
  private final long submissionTicks;

  public Wrapper(T delegate, AgentScope.Continuation continuation, long submissionTicks) {
    this.delegate = delegate;
    this.continuation = continuation;
    this.submissionTicks = submissionTicks;
  }

  @Override
  public void run() {
    try (AgentScope scope = activate()) {
      long startNano = 0L;
      ProfilerContext profilerCtx = null;
      if (scope != null) {
        AgentSpan span = scope.span();
        if (span != null && span.context() instanceof ProfilerContext) {
          profilerCtx = (ProfilerContext) span.context();
          Long pendingStart = AsyncProfiledTaskHandoff.takePendingActivationStartNano();
          // Same activation timestamp as a synthetic id for the work segment, aligned with
          // beforeExecute queue-timer + AsyncProfiledTaskHandoff when a QueueTime self-loop
          // was disambiguated.
          startNano = (pendingStart != null) ? pendingStart : System.nanoTime();
          long unblocking = profilerCtx.getSyntheticWorkSpanIdForActivation(startNano);
          // Emit a zero-blocker TaskBlock covering the queue wait (submission → activation).
          // unblocking = synthetic work segment; enables critical path to leave the base span
          // after the handoff when that SpanNode exists (onTaskDeactivation).
          if (submissionTicks > 0) {
            AgentTracer.get()
                .getProfilingContext()
                .recordTaskBlock(
                    submissionTicks,
                    profilerCtx.getSpanId(),
                    profilerCtx.getRootSpanId(),
                    0L,
                    unblocking);
          }
          AgentTracer.get().getProfilingContext().onTaskActivation(profilerCtx, startNano);
        }
      }
      try {
        delegate.run();
      } finally {
        if (profilerCtx != null) {
          AgentTracer.get().getProfilingContext().onTaskDeactivation(profilerCtx, startNano);
        }
      }
    }
  }

  public void cancel() {
    if (null != continuation) {
      continuation.cancel();
    }
  }

  public T unwrap() {
    return delegate;
  }

  private AgentScope activate() {
    return null == continuation ? null : continuation.activate();
  }

  @Override
  public void close() throws Exception {
    cancel();
    if (delegate instanceof AutoCloseable) {
      ((AutoCloseable) delegate).close();
    }
  }
}
