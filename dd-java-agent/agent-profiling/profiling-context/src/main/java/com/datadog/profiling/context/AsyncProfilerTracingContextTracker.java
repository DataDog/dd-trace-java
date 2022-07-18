package com.datadog.profiling.context;

import com.datadog.profiling.async.AsyncProfiler;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.function.ToIntFunction;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Delayed;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.jctools.maps.NonBlockingHashMapLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsyncProfilerTracingContextTracker implements TracingContextTracker {
  private static final Logger log = LoggerFactory.getLogger(AsyncProfilerTracingContextTracker.class);

  private static final AsyncProfiler asyncProfiler = AsyncProfiler.getInstance();

  private final long spanId;
  private final long rootSpanId;

  AsyncProfilerTracingContextTracker(AgentSpan span) {
    this.spanId = span != null ? span.getSpanId().toLong() : -1;
    this.rootSpanId = span != null ? span.getLocalRootSpan() != null ? span.getLocalRootSpan().getSpanId().toLong() : -1 : -1;
    System.err.printf("Create context spanId = " + spanId + " rootSpanId = " + rootSpanId + "%n");
  }

  @Override
  public void activateContext() {
    System.err.printf("Set context spanId = " + spanId + " rootSpanId = " + rootSpanId + "%n");
    asyncProfiler.setContext(spanId, rootSpanId);
  }

  @Override
  public void deactivateContext() {
    System.err.printf("Clear context spanId = " + spanId + " rootSpanId = " + rootSpanId + "%n");
    asyncProfiler.clearContext();
  }

  @Override
  public void maybeDeactivateContext() {
    System.err.printf("Clear context spanId = " + spanId + " rootSpanId = " + rootSpanId + "%n");
    //FIXME: need to get rid of this _maybe_
    asyncProfiler.clearContext();
  }

  private static byte[] emptyByteArray = new byte[0];
  private static ByteBuffer emptyByteBuffer = ByteBuffer.allocate(0);

  @Override
  public byte[] persist() {
    return emptyByteArray;
  }

  @Override
  public int persist(ToIntFunction<ByteBuffer> dataConsumer) {
    if (dataConsumer == null) {
      return 0;
    }
    return dataConsumer.applyAsInt(emptyByteBuffer);
  }

  @Override
  public boolean release() {
    return true;
  }

  @Override
  public int getVersion() {
    return 1;
  }

  @Override
  public DelayedTracker asDelayed() {
    throw new UnsupportedOperationException();
  }
}
