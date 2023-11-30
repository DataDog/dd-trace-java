package com.datadog.profiling.controller.openjdk.events;

import datadog.trace.api.Stateful;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.Timeline")
@Label("Profiler Timeline Event")
@Description("Datadog profiler timeline event")
@Category("Datadog")
@StackTrace(false)
public class TimelineEvent extends Event implements Stateful {

  @Label("Local Root Span Id")
  private final long localRootSpanId;

  @Label("Span Id")
  private final long spanId;

  @Label("Span Name")
  @Name("_dd.trace.operation")
  private final String operation;

  public TimelineEvent(long localRootSpanId, long spanId, String operation) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
    this.operation = operation;
    begin();
  }

  @Override
  public void close() {
    end();
    if (shouldCommit()) {
      commit();
    }
  }

  @Override
  public void activate(Object context) {
    // nothing to do, either we get an event or we don't
  }
}
