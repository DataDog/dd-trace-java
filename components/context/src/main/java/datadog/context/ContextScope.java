package datadog.context;

/** Controls the validity of context attached to an execution unit. */
public interface ContextScope extends AutoCloseable {
  /** Returns the context controlled by this scope. */
  Context context();

  /** Detaches the context from the execution unit. */
  @Override
  void close();

  /**
   * Closes the given scope, tolerating a {@code null} scope.
   *
   * <p>Useful in advice that attaches a scope on enter and closes it on exit: if the enter advice
   * throws before assigning the scope, the exit advice would otherwise NPE on a null scope, masking
   * the original failure.
   *
   * @param scope the scope to close; can be {@code null}.
   */
  static void close(ContextScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
