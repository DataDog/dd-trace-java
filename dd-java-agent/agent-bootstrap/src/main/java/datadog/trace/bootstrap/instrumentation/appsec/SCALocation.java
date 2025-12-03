package datadog.trace.bootstrap.instrumentation.appsec;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

/**
 * Location information for SCA (Supply Chain Analysis) detection.
 *
 * <p>Captures where in the user's code a vulnerable method was invoked.
 */
public final class SCALocation {

  @Nullable private final String path;
  private final int line;
  @Nullable private final String method;
  @Nullable private final Long spanId;
  @Nullable private final String stackId;

  private SCALocation(
      @Nullable final Long spanId,
      @Nullable final String path,
      final int line,
      @Nullable final String method,
      @Nullable final String stackId) {
    this.spanId = spanId;
    this.path = path;
    this.line = line;
    this.method = method;
    this.stackId = stackId;
  }

  /**
   * Creates a Location from a stack trace element and span.
   *
   * @param span the current span
   * @param stack the stack trace element
   * @param stackId the stack trace ID
   * @return the location
   */
  public static SCALocation forSpanAndStack(
      @Nullable final AgentSpan span,
      final StackTraceElement stack,
      @Nullable final String stackId) {
    return new SCALocation(
        spanId(span),
        stack.getClassName(),
        stack.getLineNumber(),
        stack.getMethodName(),
        stackId);
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
  public Long getSpanId() {
    return spanId;
  }

  @Nullable
  public String getStackId() {
    return stackId;
  }

  @Nullable
  private static Long spanId(@Nullable AgentSpan span) {
    return span != null ? span.getSpanId() : null;
  }
}
