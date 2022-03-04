package com.datadog.profiling.auxiliary.async;

import com.google.auto.service.AutoService;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import one.profiler.AsyncProfiler;
import one.profiler.ContextIntervals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

@AutoService(TracingContextTracker.IntervalBlobListener.class)
public final class IntervalBlobWriter implements TracingContextTracker.IntervalBlobListener {
  private static final Logger log = LoggerFactory.getLogger(IntervalBlobWriter.class);

  private static final AtomicReference<ContextIntervals> contextIntervalsRef = new AtomicReference<>();
  static void initialize(AsyncProfiler profiler) {
    contextIntervalsRef.compareAndSet(null, new ContextIntervals(profiler));
    for (EventType et : FlightRecorder.getFlightRecorder().getEventTypes()) {
      if (et.getName().contains("Context Interval")) {
        log.info("===> {}#enabled={}", et.getName(), et.isEnabled());
      }
    }
  }

  @Override
  public void onIntervalBlob(AgentSpan span, ByteBuffer blob) {
    ContextIntervals contextIntervals = contextIntervalsRef.get();
    if (contextIntervals != null) {
      contextIntervals.writeContextIntervals(span.getResourceName().toString(), blob);
    }
  }
}
