package datadog.trace.instrumentation.jetty12;

import datadog.appsec.api.blocking.BlockingException;

/**
 * Locates a {@link BlockingException} inside a Jetty failure.
 *
 * <p>Kept free of other helper dependencies so that modules which only need the lookup can inject
 * it without pulling {@code JettyDecorator} and its helpers.
 */
final class JettyBlockingExceptions {
  /** Bounds the cause walk so a cyclic or very deep cause chain cannot stall a Jetty thread. */
  private static final int MAX_CAUSE_DEPTH = 16;

  private JettyBlockingExceptions() {}

  /** Looks for a {@link BlockingException} in the cause chain and the top-level suppressed list. */
  static Throwable findBlockingException(final Throwable failure) {
    if (failure == null) {
      return null;
    }
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof BlockingException) {
        return current;
      }
      current = current.getCause();
    }
    for (final Throwable suppressed : failure.getSuppressed()) {
      if (suppressed instanceof BlockingException) {
        return suppressed;
      }
    }
    return null;
  }
}
