package com.datadog.profiling.controller.jfr.parser;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Label("Parser Event")
@Name("datadog.ParserEvent")
@Category({"datadog", "test"})
public class ParserEvent extends Event {
  @Label("value")
  private final int value;

  public ParserEvent(int value) {
    this.value = value;
  }
}
