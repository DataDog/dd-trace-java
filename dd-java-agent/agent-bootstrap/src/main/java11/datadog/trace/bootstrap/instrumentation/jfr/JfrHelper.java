package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.jfr.directallocation.DirectAllocationTotalEvent;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;

public final class JfrHelper {
  public static void addPeriodicEvent(Class<? extends Event> eventClass, Runnable eventHook) {
    if (!Platform.isNativeImageBuilder()) {
      FlightRecorder.addPeriodicEvent(DirectAllocationTotalEvent.class, eventHook);
    }
  }
}
