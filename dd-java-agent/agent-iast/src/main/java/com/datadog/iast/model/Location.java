package com.datadog.iast.model;

public final class Location {

  private final String path;

  private final int line;

  private Location(final String path, final int line) {
    this.path = path;
    this.line = line;
  }

  public static Location forStack(final StackTraceElement stack) {
    return new Location(stack.getClassName(), stack.getLineNumber());
  }

  public String getPath() {
    return path;
  }

  public int getLine() {
    return line;
  }
}
