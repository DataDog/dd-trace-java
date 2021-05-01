package com.datadog.profiling.controller.openjdk.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
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
  @Label("Available Processors")
  @Description("The number of available processors as reported by the runtime")
  private int availableProcessors;

  private AvailableProcessorsEvent() {
    this.availableProcessors = Runtime.getRuntime().availableProcessors();
  }

  public static void emit() {
    new AvailableProcessorsEvent().commit();
  }
}
