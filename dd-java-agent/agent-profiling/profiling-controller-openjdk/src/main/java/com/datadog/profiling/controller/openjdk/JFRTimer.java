package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.QueueTimeEvent;
import com.datadog.profiling.utils.ExcludedVersions;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;
import jdk.jfr.EventType;

public class JFRTimer implements Timer {

  public JFRTimer() {
    ExcludedVersions.checkVersionExclusion();
    EventType.getEventType(QueueTimeEvent.class);
  }

  @Override
  public Timing start(TimerType type) {
    if (type == TimerType.QUEUEING) {
      return new QueueTimeEvent();
    }
    return Timing.NoOp.INSTANCE;
  }
}
