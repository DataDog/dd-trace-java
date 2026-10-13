package datadog.trace.bootstrap.instrumentation.scala;

import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStore;

import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.concurrent.atomic.AtomicReference;

public final class ScalaPromiseContinuationHelper {
  private ScalaPromiseContinuationHelper() {}

  /**
   * Atomically replace a callback collection and release a continuation removed with its callback.
   *
   * <p>The identity check distinguishes a successful removal from a successful no-op CAS.
   */
  public static boolean compareAndSetAndCancel(
      AtomicReference<Object> reference,
      Object expected,
      Object replacement,
      int contextStoreId,
      Object callback) {
    boolean updated = reference.compareAndSet(expected, replacement);
    if (updated && expected != replacement) {
      State state = (State) getContextStore(contextStoreId).get(callback);
      if (null != state) {
        state.closeContinuation();
      }
    }
    return updated;
  }
}
