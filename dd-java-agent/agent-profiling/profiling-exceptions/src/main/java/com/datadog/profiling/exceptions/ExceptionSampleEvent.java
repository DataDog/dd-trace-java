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
  private volatile String message;

  public ExceptionSampleEvent(Exception e) {
    this.type = e.getClass().getName();
    try {
      this.message = e.getMessage();
    } catch (Throwable ignored) {
    }
  }

  String getMessage() {
    return message;
  }

  String getType() {
    return type;
  }
}
