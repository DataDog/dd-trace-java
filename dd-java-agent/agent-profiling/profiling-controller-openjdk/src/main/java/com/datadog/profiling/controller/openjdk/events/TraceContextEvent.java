package com.datadog.profiling.controller.openjdk.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Registered;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import jdk.jfr.Timestamp;

@Name("datadog.TraceContext")
@Label("Trace Context")
@Description("Trace context interval.")
@Category("Datadog")
@StackTrace(true)
@Registered
@Enabled
public class TraceContextEvent extends Event {
  @Label("Trace ID")
  private final long traceId;

  @Label("Interval Thread ID")
  private final long threadid;

  @Label("Interval Start Timestamp")
  @Timestamp(Timestamp.TICKS)
  private final long startTimeStamp;

  @Label("Interval Duration")
  @Timespan(Timespan.TICKS)
  private final long durationTicks;

  public TraceContextEvent(long traceId, long threaId, long startTime, long duration) {
    this.traceId = traceId;
    this.threadid = threaId;
    this.startTimeStamp = startTime;
    this.durationTicks = duration;
  }
}
