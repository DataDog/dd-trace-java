package com.datadog.profiling.jfr;

public final class Event {
  private TypedValue value;

  Event(TypedValue value) {
    this.value = value;
  }
}
