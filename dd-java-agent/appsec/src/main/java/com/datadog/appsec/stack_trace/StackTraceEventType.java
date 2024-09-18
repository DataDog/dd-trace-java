package com.datadog.appsec.stack_trace;

public enum StackTraceEventType {
  EXPLOIT("exploit"),
  VULNERABILITY("vulnerability");

  private final String name;

  StackTraceEventType(String name) {
    this.name = name;
  }

  @Override
  public String toString() {
    return this.name;
  }
}
