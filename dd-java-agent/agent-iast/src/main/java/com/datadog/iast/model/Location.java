package com.datadog.iast.model;

public final class Location {

  private final String path;

  private final int line;

  private Long spanId;

  private Location(final long spanId, final String path, final int line) {
    this.spanId = spanId == 0 ? null : spanId;
    this.path = path;
    this.line = line;
  }

  public static Location forSpanAndStack(final long spanId, final StackTraceElement stack) {
    return new Location(spanId, stack.getClassName(), stack.getLineNumber());
  }

  public long getSpanId() {
    return spanId == null ? 0 : spanId;
  }

  public String getPath() {
    return path;
  }

  public int getLine() {
    return line;
  }

  public void updateSpan(final long spanId) {
    this.spanId = spanId;
  }
}
