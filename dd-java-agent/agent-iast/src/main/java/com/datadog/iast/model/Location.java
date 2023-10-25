package com.datadog.iast.model;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class Location {

  private final String path;

  private final int line;

  private final String method;

  private Long spanId;

  private transient String serviceName;

  private Location(
      final Long spanId,
      final String path,
      final int line,
      final String method,
      final String serviceName) {
    this.spanId = spanId;
    this.path = path;
    this.line = line;
    this.method = method;
    this.serviceName = serviceName;
  }

  public static Location forSpanAndStack(final AgentSpan span, final StackTraceElement stack) {
    return new Location(
        spanId(span),
        stack.getClassName(),
        stack.getLineNumber(),
        stack.getMethodName(),
        serviceName(span));
  }

  public static Location forSpanAndClassAndMethod(
      final AgentSpan span, final String clazz, final String method) {
    return new Location(spanId(span), clazz, -1, method, serviceName(span));
  }

  public static Location forSpanAndFileAndLine(
      final AgentSpan span, final String file, final int line) {
    return new Location(spanId(span), file, line, null, serviceName(span));
  }

  public static Location forSpan(final AgentSpan span) {
    return new Location(spanId(span), null, -1, null, serviceName(span));
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

  public String getServiceName() {
    return serviceName;
  }

  public void updateSpan(final AgentSpan span) {
    if (span != null) {
      this.spanId = span.getSpanId();
      this.serviceName = span.getServiceName();
    }
  }

  private static Long spanId(AgentSpan span) {
    return span != null ? span.getSpanId() : null;
  }

  private static String serviceName(AgentSpan span) {
    return span != null ? span.getServiceName() : null;
  }
}
