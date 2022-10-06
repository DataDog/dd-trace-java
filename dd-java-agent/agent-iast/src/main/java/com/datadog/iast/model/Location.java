package com.datadog.iast.model;

import datadog.trace.api.DDId;

public final class Location {

  private final String path;

  private final int line;

  private final DDId spanId;

  private Location(final DDId spanId, final String path, final int line) {
    this.spanId = spanId;
    this.path = path;
    this.line = line;
  }

  public static Location forSpanAndStack(final DDId spanId, final StackTraceElement stack) {
    return new Location(spanId, stack.getClassName(), stack.getLineNumber());
  }

  public DDId getSpanId() {
    return spanId;
  }

  public String getPath() {
    return path;
  }

  public int getLine() {
    return line;
  }
}
