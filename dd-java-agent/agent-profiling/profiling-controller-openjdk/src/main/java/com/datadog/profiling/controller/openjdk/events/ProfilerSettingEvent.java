package com.datadog.profiling.controller.openjdk.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.ProfilerSetting")
@Label("Profiler Configuration Setting")
@Description("Datadog profiler configuration setting")
@Category("Datadog")
@StackTrace(false)
public class ProfilerSettingEvent extends Event {
  @Label("Setting Name")
  private final String name;

  @Label("Setting Value")
  private final String value;

  @Label("Setting Unit")
  private final String unit;

  public ProfilerSettingEvent(String name, String value) {
    this(name, value, "");
  }

  public ProfilerSettingEvent(String name, String value, String unit) {
    this.name = name;
    this.value = value;
    this.unit = unit;
  }
}
