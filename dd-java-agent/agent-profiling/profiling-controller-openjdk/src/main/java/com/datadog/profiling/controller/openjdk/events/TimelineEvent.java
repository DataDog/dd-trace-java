package com.datadog.profiling.controller.openjdk.events;

import com.datadog.profiling.controller.jfr.JFRAccess;
import datadog.trace.api.Stateful;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

@Name("datadog.Timeline")
@Label("Profiler Timeline Event")
@Description("Datadog profiler timeline event")
@Category("Datadog")
@StackTrace(false)
public class TimelineEvent extends Event implements Stateful {
  private static final Logger log = LoggerFactory.getLogger(TimelineEvent.class);

  @Label("Local Root Span Id")
  private final long localRootSpanId;

  @Label("Span Id")
  private final long spanId;

  @Label("Span Name")
  @Name("_dd.trace.operation")
  private final String operation;

  private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
  private static final boolean isThreadCpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();

  private long marker = -1;
  private long cpuTime = -1;

  public TimelineEvent(long localRootSpanId, long spanId, String operation) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
    this.operation = operation;
    begin();
    cpuTime = isThreadCpuTimeSupported ? threadMXBean.getThreadCpuTime(Thread.currentThread().getId()) : -1;
    marker = JFRAccess.instance().getThreadWriterPosition();
  }

  @Override
  public void close() {
    long end = JFRAccess.instance().getThreadWriterPosition();
    boolean writeEvent = end != marker;
    if (!writeEvent && isThreadCpuTimeSupported) {
      writeEvent = threadMXBean.getThreadCpuTime(Thread.currentThread().getId()) != cpuTime;
    }
    if (writeEvent) {
      end();
      if (shouldCommit()) {
        commit();
      }
    }
  }

  @Override
  public void activate(Object context) {
    // nothing to do, either we get an event or we don't
  }
}
