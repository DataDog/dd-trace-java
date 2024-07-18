package datadog.context;

/**
 * A scope representing an attached context for an execution unit. Closing the scope will detach the
 * context and restore the previous one.
 *
 * <p>* Scopes are intended to be used with {@code try-with-resources} block:
 *
 * <pre>{@code
 * try (Scope ignored = span.makeCurrent()) {
 *   // Execution unit
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ContextScope extends AutoCloseable {
  @Override
  void close(); // Should not throw exception
}
