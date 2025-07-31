package com.datadog.profiling.controller.openjdk.events;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.profiling.controller.jfr.JFRAccess;
import datadog.trace.api.Stateful;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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

    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private static final boolean isThreadCpuTimeSupported;

    static {
      boolean cpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();
      if (cpuTimeSupported) {
        // a trivial benchmark
        final int iterations = 1_000;
        long ts = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < iterations; ++i) {
          sum += threadMXBean.getThreadCpuTime(Thread.currentThread().getId());
        }
        double avg = (System.nanoTime() - ts) / (double) iterations;
        // This, practically, will always be true; we just need to make sure JIT does not optimize
        // the summing code away
        if (sum > 0) {
          cpuTimeSupported = avg < 5_000; // less than 5us for getting thread cpu time sounds fair
        }
        log.debug(
            SEND_TELEMETRY,
            "Thread cpu time cost: "
                + avg
                + "ns, cpu time collection "
                + (cpuTimeSupported ? "enabled" : "disabled"));
      } else {
        log.debug(SEND_TELEMETRY, "Thread cpu time not supported");
      }
      isThreadCpuTimeSupported = cpuTimeSupported;
    }

    private final long marker;
    private final long cpuTime;

    public Holder(long localRootSpanId, long spanId, String operation) {
      this.event = new TimelineEvent(localRootSpanId, spanId, operation);
      this.cpuTime =
          isThreadCpuTimeSupported
              ? threadMXBean.getThreadCpuTime(Thread.currentThread().getId())
              : -1;
      this.marker = JFRAccess.instance().getThreadWriterPosition();
      this.event.begin();
    }

    public void begin() {}

    @Override
    public void close() {
      long end = JFRAccess.instance().getThreadWriterPosition();
      boolean writeEvent = end != marker;
      if (!writeEvent && isThreadCpuTimeSupported) {
        writeEvent = threadMXBean.getThreadCpuTime(Thread.currentThread().getId()) != cpuTime;
      }
      if (writeEvent) {
        event.close();
      }
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
