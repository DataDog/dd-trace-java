package testdog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import java.util.concurrent.StructuredTaskScope;

/** Adapts the Java 27 Joiner API to the shared cancellation tests. */
@SuppressWarnings("preview")
class TestJoiner<T> implements StructuredTaskScope.Joiner<T, Void, RuntimeException> {
  @Override
  public boolean onFork(StructuredTaskScope.Subtask<T> subtask) {
    return onFork();
  }

  boolean onFork() {
    return false;
  }

  @Override
  public Void result() {
    return null;
  }

  @Override
  public Void timeout() {
    return null;
  }
}
