package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.TraceContextEvent;
import datadog.trace.api.profiling.CustomEventAccess;
import jdk.jfr.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CustomEventAccessImpl implements CustomEventAccess {
  private static final Logger log = LoggerFactory.getLogger(CustomEventAccessImpl.class);

  public CustomEventAccessImpl() {
    EventType type = EventType.getEventType(TraceContextEvent.class);
  }

  @Override
  public void emitTraceContextEvent(
      long localRootSpanId, long threadId, long startTime, long duration) {
    //    TraceContextEvent event = new TraceContextEvent(localRootSpanId, threadId, startTime,
    // duration);
    //    event.commit();
  }
}
