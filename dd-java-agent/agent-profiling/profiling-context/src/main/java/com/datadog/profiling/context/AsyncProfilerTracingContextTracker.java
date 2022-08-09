package com.datadog.profiling.context;

import com.datadog.profiling.async.AsyncProfiler;
import datadog.trace.api.function.ToIntFunction;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public final class AsyncProfilerTracingContextTracker implements TracingContextTracker {

  private static final AsyncProfiler ASYNC_PROFILER = AsyncProfiler.getInstance();

  private static final class Context {
    private final long spanId;
    private final long rootSpanId;

    public Context(long spanId, long rootSpanId) {
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }

    public long spanId() {
      return spanId;
    }

    public long rootSpanId() {
      return rootSpanId;
    }

    public boolean equals(long spanId, long rootSpanId) {
      return this.spanId == spanId && this.rootSpanId == rootSpanId;
    }
  }

  private static final ThreadLocal<ArrayDeque<Context>> contextsThreadLocal =
      new ThreadLocal<ArrayDeque<Context>>() {
        @Override
        protected ArrayDeque<Context> initialValue() {
          return new ArrayDeque<Context>();
        }
      };

  private final long spanId;
  private final long rootSpanId;

  AsyncProfilerTracingContextTracker(AgentSpan span) {
    this.spanId = span != null ? span.getSpanId().toLong() : -1;
    this.rootSpanId =
        span != null
            ? span.getLocalRootSpan() != null ? span.getLocalRootSpan().getSpanId().toLong() : -1
            : -1;

    activateAsyncProfilerContext();
  }

  @Override
  public void activateContext() {
    activateAsyncProfilerContext();
  }

  @Override
  public void deactivateContext() {
    deactivateAsyncProfilerContext();
  }

  @Override
  public void maybeDeactivateContext() {
    deactivateAsyncProfilerContext();
  }

  private void activateAsyncProfilerContext() {
    ArrayDeque<Context> contexts = contextsThreadLocal.get();

    contexts.push(new Context(spanId, rootSpanId));
    ASYNC_PROFILER.setContext(spanId, rootSpanId);
  }

  private void deactivateAsyncProfilerContext() {
    ArrayDeque<Context> contexts = contextsThreadLocal.get();

    while (!contexts.isEmpty()) {
      // pop until we peeled the stack to where we pushed it in the activation
      Context context = contexts.pop();
      if (context.equals(spanId, rootSpanId)) {
        break;
      }
    }

    if (contexts.isEmpty()) {
      ASYNC_PROFILER.clearContext();
    } else {
      Context context = contexts.peek();
      ASYNC_PROFILER.setContext(context.spanId(), context.rootSpanId());
    }
  }

  @Override
  public byte[] persist() {
    return null;
  }

  @Override
  public int persist(ToIntFunction<ByteBuffer> dataConsumer) {
    return 0;
  }

  @Override
  public boolean release() {
    return false;
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public DelayedTracker asDelayed() {
    return DelayedTracker.EMPTY;
  }
}
