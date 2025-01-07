package datadog.context;

/** Controls the validity of context attached to an execution unit. */
public interface ContextScope extends AutoCloseable {
  /** Returns the context controlled by this scope. */
  Context context();

  /** Detaches the context from the execution unit. */
  @Override
  void close();
}
