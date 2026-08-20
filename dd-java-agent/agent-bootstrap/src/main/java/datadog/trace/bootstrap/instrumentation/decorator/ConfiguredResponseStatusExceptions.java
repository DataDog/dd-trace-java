package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ClassHierarchyIterable;
import java.util.Map;

/**
 * Lets users teach the tracer about their own, framework-agnostic exception types via {@code
 * trace.response-status.exceptions} ({@code DD_TRACE_RESPONSE_STATUS_EXCEPTIONS}): a list of {@code
 * fully.qualified.ExceptionClass#accessorMethod} entries. When a configured exception (or a
 * subclass of one) is thrown from a request handler, the named no-arg accessor is invoked
 * reflectively and its numeric return value is used as the HTTP status for deciding whether the
 * span should be flagged as an error, instead of unconditionally marking it as an error.
 *
 * <p>Only ever reflects on classes/methods the user explicitly named, unlike a generic heuristic
 * that would probe arbitrary exceptions for common accessor names and risk silently clearing a
 * genuine error whose exception happens to have a same-named, unrelated method.
 */
public final class ConfiguredResponseStatusExceptions {

  public static Integer extractStatus(final Throwable throwable) {
    Map<String, String> accessors = Config.get().getResponseStatusExceptionAccessors();
    if (accessors.isEmpty()) {
      return null;
    }
    Throwable current = throwable;
    for (int depth = 0; current != null && depth < 5; depth++, current = current.getCause()) {
      for (Class<?> type : new ClassHierarchyIterable(current.getClass())) {
        String methodName = accessors.get(type.getName());
        if (methodName != null) {
          Integer status = invoke(current, methodName);
          if (status != null) {
            return status;
          }
        }
      }
    }
    return null;
  }

  private static Integer invoke(final Throwable throwable, final String methodName) {
    try {
      Object result = throwable.getClass().getMethod(methodName).invoke(throwable);
      if (result instanceof Number) {
        int status = ((Number) result).intValue();
        // Guard against sentinel values (e.g. -1 for "unknown") reflectively returned by a
        // misconfigured accessor: an out-of-range status would otherwise throw when later used
        // to index into Config#getHttpServerErrorStatuses, aborting normal error handling.
        if (status >= 100 && status <= 599) {
          return status;
        }
      }
    } catch (Throwable ignored) {
      // misconfigured entry (wrong method name, non-numeric return, etc.) -- fall through
    }
    return null;
  }

  private ConfiguredResponseStatusExceptions() {}
}
