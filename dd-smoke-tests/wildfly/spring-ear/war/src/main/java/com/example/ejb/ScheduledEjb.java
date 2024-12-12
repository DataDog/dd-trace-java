package com.example.ejb;

import static com.example.Common.ENABLED;

import datadog.trace.api.Trace;
import javax.ejb.Schedule;
import javax.ejb.Stateless;

@Stateless
public class ScheduledEjb {

  @Schedule(second = "*/1", minute = "*", hour = "*")
  public void runIt() {
    if (ENABLED.getAndSet(false)) {
      generateSomeTrace();
    }
  }

  @Trace
  private void generateSomeTrace() {
    // empty
  }
}
