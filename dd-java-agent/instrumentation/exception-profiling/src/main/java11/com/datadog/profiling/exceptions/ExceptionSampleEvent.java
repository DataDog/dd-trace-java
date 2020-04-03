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
  private final String type;

  @Label("Exception message")
  private final String message;

  /**
   * JFR may truncate the stack trace - so store original length as well.
   */
  @Label("Exception stackdepth")
  private final int stackDepth;

  @Label("Sampled")
  private final boolean sampled;

  @Label("First occurrence")
  private final boolean firstOccurrence;

  public ExceptionSampleEvent(final Exception e, final boolean sampled, final boolean firstOccurrence) {
    type = e.getClass().getName();
    message = e.getMessage();
    stackDepth = e.getStackTrace().length;
    this.sampled = sampled;
    this.firstOccurrence = firstOccurrence;
  }

  // used in tests only
  String getType() {
    return type;
  }
}
