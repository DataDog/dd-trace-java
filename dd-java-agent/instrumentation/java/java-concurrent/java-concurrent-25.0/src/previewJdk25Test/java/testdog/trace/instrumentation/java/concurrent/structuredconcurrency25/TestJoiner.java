package testdog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import java.util.concurrent.StructuredTaskScope;

/** Adapts the Java 25 Joiner API to the shared cancellation tests. */
@SuppressWarnings("preview")
class TestJoiner<T> implements StructuredTaskScope.Joiner<T, Void> {
  @Override
  public boolean onFork(StructuredTaskScope.Subtask<? extends T> subtask) {
    return onFork();
  }

  boolean onFork() {
    return false;
  }

  @Override
  public Void result() {
    return null;
  }
}
