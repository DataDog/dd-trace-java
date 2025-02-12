package com.datadog.iast.model;

import com.squareup.moshi.Json;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public final class Location {

  @Nullable private final String path;

  private final int line;

  @Nullable private final String method;

  @Nullable private Long spanId;

  @Nullable private transient String serviceName;

  @Nullable
  @Json(name = "class")
  private final String className;

  private Location(
      @Nullable final Long spanId,
      @Nullable final String path,
      final int line,
      @Nullable final String className,
      @Nullable final String method,
      @Nullable final String serviceName) {
    this.spanId = spanId;
    this.path = path;
    this.line = line;
    this.method = method;
    this.serviceName = serviceName;
    this.className = className;
  }

  public static Location forSpanAndStack(
      @Nullable final AgentSpan span, final StackTraceElement stack) {
    return new Location(
        spanId(span),
        stack.getFileName(),
        stack.getLineNumber(),
        stack.getClassName(),
        stack.getMethodName(),
        serviceName(span));
  }

  public static Location forSpanAndClassAndMethod(
      @Nullable final AgentSpan span, final String clazz, final String method) {
    return new Location(spanId(span), null, -1, clazz, method, serviceName(span));
  }

  public static Location forSpanAndFileAndLine(
      @Nullable final AgentSpan span, final String file, final int line) {
    return new Location(spanId(span), file, line, null, null, serviceName(span));
  }

  public static Location forSpan(@Nullable final AgentSpan span) {
    return new Location(spanId(span), null, -1, null, null, serviceName(span));
  }

  public static Location forClassAndMethodAndLine(String clazz, String method, int currentLine) {
    return new Location(null, null, currentLine, clazz, method, null);
  }

  public long getSpanId() {
    return spanId == null ? 0 : spanId;
  }

  @Nullable
  public String getPath() {
    return path;
  }

  public int getLine() {
    return line;
  }

  @Nullable
  public String getMethod() {
    return method;
  }

  @Nullable
  public String getServiceName() {
    return serviceName;
  }

  @Nullable
  public String getClassName() {
    return className;
  }

  public void updateSpan(@Nullable final AgentSpan span) {
    if (span != null) {
      this.spanId = span.getSpanId();
      this.serviceName = span.getServiceName();
    }
  }

  @Nullable
  private static Long spanId(@Nullable AgentSpan span) {
    return span != null ? span.getSpanId() : null;
  }

  @Nullable
  private static String serviceName(@Nullable AgentSpan span) {
    return span != null ? span.getServiceName() : null;
  }
}
