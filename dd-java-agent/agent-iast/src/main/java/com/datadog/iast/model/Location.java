package com.datadog.iast.model;

import datadog.trace.api.DDSpanId;

public final class Location {

  private final String path;

  private final int line;

  private final DDSpanId spanId;

  private Location(final DDSpanId spanId, final String path, final int line) {
    this.spanId = spanId;
    this.path = path;
    this.line = line;
  }

  public static Location forSpanAndStack(final DDSpanId spanId, final StackTraceElement stack) {
    return new Location(spanId, stack.getClassName(), stack.getLineNumber());
  }

  public DDSpanId getSpanId() {
    return spanId;
  }

  public String getPath() {
    return path;
  }

  public int getLine() {
    return line;
  }
}
