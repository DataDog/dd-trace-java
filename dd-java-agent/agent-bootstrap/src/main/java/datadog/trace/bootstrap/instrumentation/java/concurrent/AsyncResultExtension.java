package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * This interface defines asynchronous result type support extension. It allows deferring the
 * support implementations where types are available on classpath.
 */
public interface AsyncResultExtension {
  /**
   * Checks whether this extensions support a result type.
   *
   * @param result The result type to check.
   * @return {@code true} if the type is supported by this extension, {@code false} otherwise.
   */
  boolean supports(Class<?> result);

  /**
   * Applies the extension to the async result.
   *
   * @param result The async result.
   * @param span The related span.
   * @return The result object to return (can be the original result if not modified), or {@code
   *     null} if the extension could not be applied.
   */
  Object apply(Object result, AgentSpan span);
}
