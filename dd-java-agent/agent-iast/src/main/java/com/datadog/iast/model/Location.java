package com.datadog.iast.model;

public final class Location {

  private final String path;

  private final int line;

  private final String method;

  private Long spanId;

  private Location(final long spanId, final String path, final int line, final String method) {
    this.spanId = spanId == 0 ? null : spanId;
    this.path = path;
    this.line = line;
    this.method = method;
  }

  public static Location forSpanAndStack(final long spanId, final StackTraceElement stack) {
    return new Location(spanId, stack.getClassName(), stack.getLineNumber(), stack.getMethodName());
  }

  public static Location forSpanAndClassAndMethod(
      final long spanId, final String clazz, final String method) {
    return new Location(spanId, clazz, -1, method);
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

  public String getMethod() {
    return method;
  }

  public void updateSpan(final long spanId) {
    this.spanId = spanId;
  }
}
