package com.datadog.profiling.ddprof;

import datadog.environment.ThreadSupport;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class DatadogProfilingIntegration implements ProfilingContextIntegration {

  private static final DatadogProfiler DDPROF = DatadogProfiler.newInstance();
  private static final int SPAN_NAME_INDEX = DDPROF.operationNameOffset();
  private static final int RESOURCE_NAME_INDEX = DDPROF.resourceNameOffset();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  private static final boolean IS_ENDPOINT_COLLECTION_ENABLED =
      DatadogProfilerConfig.isEndpointTrackingEnabled();

  // don't use Config because it may use ThreadPoolExecutor to initialize itself
  private static final boolean IS_PROFILING_QUEUEING_TIME_ENABLED =
      DatadogProfilerConfig.isQueueTimeEnabled();

  private final Stateful contextManager =
      new Stateful() {
        @Override
        public void close() {
          DDPROF.clearSpanContext();
          DDPROF.clearContextValue(SPAN_NAME_INDEX);
          DDPROF.clearContextValue(RESOURCE_NAME_INDEX);
        }

        @Override
        public void activate(Object context) {
          if (context instanceof ProfilerContext) {
            ProfilerContext profilerContext = (ProfilerContext) context;
            DDPROF.setSpanContext(profilerContext.getSpanId(), profilerContext.getRootSpanId());
            DDPROF.setContextValue(SPAN_NAME_INDEX, profilerContext.getEncodedOperationName());
            DDPROF.setContextValue(RESOURCE_NAME_INDEX, profilerContext.getEncodedResourceName());
          }
        }
      };

  @Override
  public Stateful newScopeState(ProfilerContext profilerContext) {
    return contextManager;
  }

  @Override
  public void onAttach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.addThread();
    }
  }

  @Override
  public void onDetach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.removeThread();
    }
  }

  @Override
  public int encode(CharSequence constant) {
    return DDPROF.encode(constant);
  }

  @Override
  public int encodeOperationName(CharSequence constant) {
    if (SPAN_NAME_INDEX >= 0) {
      return DDPROF.encode(constant);
    }
    return 0;
  }

  @Override
  public int encodeResourceName(CharSequence constant) {
    if (RESOURCE_NAME_INDEX >= 0) {
      return DDPROF.encode(constant);
    }
    return 0;
  }

  @Override
  public String name() {
    return "ddprof";
  }

  @Override
  public long getCurrentTicks() {
    return DDPROF.getCurrentTicks();
  }

  @Override
  public void recordTaskBlock(
      long startTicks, long spanId, long rootSpanId, long blocker, long unblockingSpanId) {
    DDPROF.recordTaskBlockEvent(startTicks, spanId, rootSpanId, blocker, unblockingSpanId);
  }

  @Override
  public void parkEnter(long spanId, long rootSpanId) {
    DDPROF.parkEnter(spanId, rootSpanId);
  }

  @Override
  public void parkExit(long blocker, long unblockingSpanId) {
    DDPROF.parkExit(blocker, unblockingSpanId);
  }

  @Override
  public void onSpanFinished(AgentSpan span) {
    if (span == null || !(span.context() instanceof ProfilerContext)) return;
    ProfilerContext ctx = (ProfilerContext) span.context();
    DDPROF.recordSpanNodeEvent(
        ctx.getSpanId(),
        ctx.getParentSpanId(),
        ctx.getRootSpanId(),
        span.getStartTime(),
        span.getDurationNano(),
        ctx.getEncodedOperationName(),
        ctx.getEncodedResourceName());
    // Emit the actual execution thread captured in finishAndAddToTrace() so the backend can
    // correctly attribute each span to the thread that ran it, rather than the event loop thread
    // that calls CoreTracer.write() and commits the SpanNode event above.
    long executionThreadId = ctx.getExecutionThreadId();
    String executionThreadName = ctx.getExecutionThreadName();
    if (executionThreadId > 0 && executionThreadName != null && !executionThreadName.isEmpty()) {
      SpanExecutionThreadEvent event = new SpanExecutionThreadEvent();
      event.spanId = ctx.getSpanId();
      event.executionThreadId = executionThreadId;
      event.executionThreadName = executionThreadName;
      event.commit();
    }
  }

  @Override
  public void onTaskActivation(ProfilerContext profilerContext, long startTicks) {
    // Capture the worker thread as the execution thread at the moment a task starts executing.
    // Using first-write-wins semantics in DDSpanContext.captureExecutionThread() means this
    // worker-thread attribution is protected: if phasedFinish() is later called from a Netty
    // event loop callback (a different, wrong thread), it becomes a no-op.
    if (profilerContext != null && !ThreadSupport.isVirtual()) {
      Thread t = Thread.currentThread();
      profilerContext.captureExecutionThread(t.getId(), t.getName());
    }
  }

  @Override
  public void onTaskDeactivation(ProfilerContext profilerContext, long startNano) {
    if (profilerContext == null) {
      return;
    }
    long endNano = System.nanoTime();
    long durationNanos = endNano - startNano;
    if (durationNanos <= 0) {
      return;
    }
    // Compute epoch offset fresh each time: avoids cumulative drift between System.nanoTime()
    // (monotonic, ignores NTP/wall-clock adjustments) and System.currentTimeMillis() over the
    // JVM lifetime. Residual error is bounded by the 1 ms resolution of currentTimeMillis().
    long epochOffset = System.currentTimeMillis() * 1_000_000L - endNano;
    long startNanos = startNano + epochOffset;
    // Thread.currentThread().getId() returns the virtual thread's JVM-assigned ID when called
    // inside a virtual thread — that is intentional: it contributes uniqueness to the synthetic
    // span ID without affecting thread attribution.  The native profiler captures the OS thread
    // ID (ProfiledThread::currentTid()) for EVENT_THREAD, which is always the ForkJoin carrier
    // thread that is physically executing the virtual thread at this point.  The resulting
    // SpanNode is therefore attributed to the carrier in JFR, making it visible in the critical
    // path as a ForkJoin worker rather than disappearing into the unattributed lane.
    long syntheticSpanId =
        profilerContext.getSpanId() ^ ((long) Thread.currentThread().getId() << 32) ^ startNano;
    DDPROF.recordSpanNodeEvent(
        syntheticSpanId,
        profilerContext.getSpanId(),
        profilerContext.getRootSpanId(),
        startNanos,
        durationNanos,
        profilerContext.getEncodedOperationName(),
        profilerContext.getEncodedResourceName());
    // Emit a SpanExecutionThread event so CausalDagExtractor can override the native
    // profiler's OS-tid-based EVENT_THREAD attribution with the Java thread ID used by the
    // JFR timeline.  Without this, the critical path segment for the synthetic span may land
    // in a non-existent row (invisible) or a row whose thread-activity intervals don't match
    // (arrow over blank space).  This mirrors what onSpanFinished() does for real spans.
    // Skip for virtual threads: Thread.currentThread() returns the virtual thread object
    // (whose JVM-assigned ID has no JFR timeline lane). For virtual threads the native
    // profiler's EVENT_THREAD already resolves to the ForkJoin carrier via CPOOL and must
    // not be overridden.
    if (!ThreadSupport.isVirtual()) {
      SpanExecutionThreadEvent syntheticThreadEvent = new SpanExecutionThreadEvent();
      syntheticThreadEvent.spanId = syntheticSpanId;
      syntheticThreadEvent.executionThreadId = Thread.currentThread().getId();
      syntheticThreadEvent.executionThreadName = Thread.currentThread().getName();
      syntheticThreadEvent.commit();
    }
  }

  public void clearContext() {
    DDPROF.clearSpanContext();
    DDPROF.clearContextValue(SPAN_NAME_INDEX);
    DDPROF.clearContextValue(RESOURCE_NAME_INDEX);
  }

  @Override
  public ProfilingContextAttribute createContextAttribute(String attribute) {
    return new DatadogProfilerContextSetter(attribute, DDPROF);
  }

  @Override
  public ProfilingScope newScope() {
    return new DatadogProfilingScope(DDPROF);
  }

  @Override
  public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
    if (IS_ENDPOINT_COLLECTION_ENABLED && rootSpan != null) {
      CharSequence resourceName = rootSpan.getResourceName();
      CharSequence operationName = rootSpan.getOperationName();
      if (resourceName != null && operationName != null) {
        long startTicks =
            (tracker instanceof RootSpanTracker) ? ((RootSpanTracker) tracker).startTicks : 0L;
        long parentSpanId = 0L;
        if (rootSpan.context() instanceof ProfilerContext) {
          parentSpanId = ((ProfilerContext) rootSpan.context()).getParentSpanId();
        }
        DDPROF.recordTraceRoot(
            rootSpan.getSpanId(),
            parentSpanId,
            startTicks,
            resourceName.toString(),
            operationName.toString());
      }
    }
  }

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return new RootSpanTracker(DDPROF.getCurrentTicks());
  }

  @Override
  public Timing start(TimerType type) {
    if (IS_PROFILING_QUEUEING_TIME_ENABLED && type == TimerType.QUEUEING) {
      AgentSpan span = AgentTracer.activeSpan();
      long submittingSpanId = 0L;
      if (span != null && span.context() instanceof ProfilerContext) {
        submittingSpanId = ((ProfilerContext) span.context()).getSpanId();
      }
      return DDPROF.newQueueTimeTracker(submittingSpanId);
    }
    return Timing.NoOp.INSTANCE;
  }

  /**
   * Captures the TSC tick at root span start so we can emit real duration in the Endpoint event.
   */
  private static final class RootSpanTracker implements EndpointTracker {
    final long startTicks;

    RootSpanTracker(long startTicks) {
      this.startTicks = startTicks;
    }

    @Override
    public void endpointWritten(AgentSpan span) {}
  }
}
