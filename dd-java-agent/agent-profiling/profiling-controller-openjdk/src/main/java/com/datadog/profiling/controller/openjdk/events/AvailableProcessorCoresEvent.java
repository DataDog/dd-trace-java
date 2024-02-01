package com.datadog.profiling.controller.openjdk.events;

import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;

@Name("datadog.AvailableProcessorCores")
@Label("Available Processor Core Count")
@Description("The number of available processor cores as reported by the runtime")
@Category("Datadog")
@Period("beginChunk")
@Enabled
public class AvailableProcessorCoresEvent extends Event {
  private static final AtomicBoolean registered = new AtomicBoolean(false);

  @Label("Available Processor Cores")
  @Description("The number of available processor cores as reported by the runtime")
  private int availableProcessorCores;

  private AvailableProcessorCoresEvent() {
    this.availableProcessorCores = Runtime.getRuntime().availableProcessors();
  }

  public static void emit() {
    new AvailableProcessorCoresEvent().commit();
  }

  public static void register() {
    // Make sure the periodic event is registered only once
    if (registered.compareAndSet(false, true)) {
      JfrHelper.addPeriodicEvent(
          AvailableProcessorCoresEvent.class, AvailableProcessorCoresEvent::emit);
    }
  }
}
