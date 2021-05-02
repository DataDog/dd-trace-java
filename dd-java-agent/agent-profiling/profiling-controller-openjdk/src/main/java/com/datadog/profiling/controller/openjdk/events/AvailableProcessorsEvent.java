package com.datadog.profiling.controller.openjdk.events;

import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;

@Name("datadog.AvailableProcessors")
@Label("Available Processors Count")
@Description("The number of available processors as reported by the runtime")
@Category("Datadog")
@Period("beginChunk")
@Enabled
public class AvailableProcessorsEvent extends Event {
  private static final AtomicBoolean registered = new AtomicBoolean(false);

  @Label("Available Processors")
  @Description("The number of available processors as reported by the runtime")
  private int availableProcessors;

  private AvailableProcessorsEvent() {
    this.availableProcessors = Runtime.getRuntime().availableProcessors();
  }

  public static void emit() {
    new AvailableProcessorsEvent().commit();
  }

  public static void register() {
    // Make sure the periodic event is registered only once
    if (registered.compareAndSet(false, true)) {
      FlightRecorder.addPeriodicEvent(
          AvailableProcessorsEvent.class, AvailableProcessorsEvent::emit);
    }
  }
}
