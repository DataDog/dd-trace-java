package com.datadog.profiling.controller.openjdk.events;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.controller.jfr.JFRAccess;
import datadog.trace.api.Stateful;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.LongAdder;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Name("datadog.Timeline")
@Label("Profiler Timeline Event")
@Description("Datadog profiler timeline event")
@Category("Datadog")
@StackTrace(false)
public class TimelineEvent extends Event {
  private static final Logger log = LoggerFactory.getLogger(TimelineEvent.class);

  public static final class Holder implements Stateful {
    private final TimelineEvent event;

    private static final MethodHandle selfThreadCpuTimeMh;
    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private static final boolean isThreadCpuTimeSupported;

    static {
      boolean cpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();
      // default to a method handle that always returns -1
      MethodHandle opHandle = MethodHandles.constant(long.class, -1L);

      if (cpuTimeSupported) {
        try {
          Class<?> callTarget = Class.forName("sun.management.ThreadImpl");
          Method targetMethod = callTarget.getDeclaredMethod("getThreadTotalCpuTime0", long.class);
          targetMethod.setAccessible(true);

          // Replace the opHandle with the real method to get the thread cpu time.
          // The target method is 'sun.management.ThreadImpl.getThreadTotalCpuTime0(tid)` -
          // but for the self thread cpu time the tid will always be 0
          opHandle = MethodHandles.insertArguments(MethodHandles.lookup().unreflect(targetMethod),0, 0L);

          // a trivial benchmark
          final int iterations = 20_000;
          long ts = System.nanoTime();
          long sum = 0;
          for (int i = 0; i < iterations; ++i) {
            sum += (long)opHandle.invokeExact();
            if (i % 1_000 == 0) {
              // sanity test to bail out quickly if the call to get thread cpu time is unexpectedly slow
              if (System.nanoTime() - ts > 500_000_000L) {
                log.warn("Obtaining thread CPU time is exceptionally slow. Disabling.");
                cpuTimeSupported = false;
                break;
              }
            }
          }
          double avg = (System.nanoTime() - ts) / (double) iterations;
          // This, practically, will always be true; we just need to make sure JIT does not optimize
          // the summing code away
          if (sum > 0) {
            cpuTimeSupported = avg < 1_000; // less than 5us for getting thread cpu time sounds fair
          }
          log.debug(
              SEND_TELEMETRY,
              "Thread cpu time cost: "
                  + avg
                  + "ns, cpu time collection "
                  + (cpuTimeSupported ? "enabled" : "disabled"));
        } catch (Throwable t) {
          log.debug("Boom", t);
          cpuTimeSupported = false;
        }
      } else {
        log.debug(SEND_TELEMETRY, "Thread cpu time not supported");
      }
      selfThreadCpuTimeMh = opHandle;
      isThreadCpuTimeSupported = cpuTimeSupported;
    }

    private static final LongAdder droppedEvents = new LongAdder();
    private static final LongAdder writtenEvents = new LongAdder();
    private static final LongAdder timeChecks = new LongAdder();
    private static final LongAdder inflight = new LongAdder();

    public static void debug() {
      log.debug("Dropped timeline events: {}, written timeline events: {}, cpu time checks: {}, inflight: {}", droppedEvents.sumThenReset(), writtenEvents.sumThenReset(), timeChecks.sumThenReset(), inflight.sum());
    }

    private final long marker;
    private final long cpuTimeThreshold;

    public Holder(long localRootSpanId, long spanId, String operation) {
      this.event = new TimelineEvent(localRootSpanId, spanId, operation);
      this.cpuTimeThreshold =
          isThreadCpuTimeSupported
              ? threadMXBean.getThreadCpuTime(Thread.currentThread().getId()) + 5_000_000L
              : -1;
      this.marker = JFRAccess.instance().getThreadWriterPosition();
      this.event.begin();
      inflight.increment();
    }

    public void begin() {}

    @Override
    public void close() {
      long end = JFRAccess.instance().getThreadWriterPosition();
      boolean writeEvent = end != marker;
      if (!writeEvent && isThreadCpuTimeSupported) {
        timeChecks.increment();
        writeEvent = threadMXBean.getThreadCpuTime(Thread.currentThread().getId()) >= cpuTimeThreshold;
      }
      if (writeEvent) {
        event.close();
        writtenEvents.increment();
      } else {
        droppedEvents.increment();
      }
      inflight.decrement();
    }

    @Override
    public void activate(Object context) {
      // nothing to do, either we get an event or we don't
    }
  }

  @Label("Local Root Span Id")
  private final long localRootSpanId;

  @Label("Span Id")
  private final long spanId;

  @Label("Span Name")
  @Name("_dd.trace.operation")
  private final String operation;

  TimelineEvent(long localRootSpanId, long spanId, String operation) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
    this.operation = operation;
    begin();
  }

  void close() {
    end();
    if (shouldCommit()) {
      commit();
    }
  }
}
