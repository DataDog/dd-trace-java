package com.datadog.profiling.exceptions;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.ExceptionSample")
@Label("ExceptionSample")
@Description("Datadog exception sample event.")
@Category("Datadog")
public class ExceptionSampleEvent extends Event {
  @Label("Exception Type")
  private String type;

  @Label("Exception message")
  private final String message;

  @Label("Exception stackdepth")
  private final int stackDepth;

  public ExceptionSampleEvent(Exception e) {
    this.type = e.getClass().getName();
    this.message = e.getMessage();
    this.stackDepth = e.getStackTrace().length;
  }

  // used in tests only
  String getType() {
    return type;
  }
}
