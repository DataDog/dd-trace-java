package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * Shared tail of {@code doOnError} for HTTP server decorators that map an exception to a status
 * (via {@code @ResponseStatus}, {@code ResponseStatusException}/{@code ErrorResponse}, {@code
 * WebApplicationException}, or a configured accessor via {@link
 * ConfiguredResponseStatusExceptions}) instead of unconditionally flagging the span as an error.
 */
public final class MappedExceptionStatus {

  // Cause chains are walked no deeper than this when looking for a mapped status, to bound the
  // cost of a long chain and avoid an unbounded loop if a cause chain is ever cyclic.
  public static final int MAX_CAUSE_CHAIN_DEPTH = 5;

  private MappedExceptionStatus() {}

  /**
   * If {@code status} is a valid HTTP status, flags {@code span} as an error only if it is one of
   * the configured "server error" statuses, and returns true. Returns false, without touching the
   * span, if {@code status} is null or out of range so the caller can fall back to its default
   * error handling.
   */
  public static boolean flagIfPresent(
      final AgentSpan span,
      final Throwable throwable,
      final byte errorPriority,
      final Integer status) {
    if (status == null || status < 100 || status > 599) {
      return false;
    }
    span.addThrowable(throwable, errorPriority);
    span.setError(Config.get().getHttpServerErrorStatuses().get(status), errorPriority);
    return true;
  }
}
