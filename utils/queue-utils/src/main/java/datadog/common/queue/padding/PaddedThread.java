package datadog.common.queue.padding;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Padded volatile Thread to prevent false sharing. */
public final class PaddedThread extends ThreadRhsPadding {

  public static final VarHandle VALUE_HANDLE;

  static {
    try {
      VALUE_HANDLE =
          MethodHandles.lookup().findVarHandle(PaddedThread.class, "value", Thread.class);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  /**
   * @param newValue value to store with opaque semantics (bitwise atomicity only)
   */
  public void setOpaque(Thread newValue) {
    VALUE_HANDLE.setOpaque(this, newValue);
  }

  /**
   * @param newValue value to store with volatile semantics (full visibility)
   */
  public void setVolatile(Thread newValue) {
    VALUE_HANDLE.setVolatile(this, newValue);
  }

  /**
   * @param newValue new value
   * @return previous value
   */
  public Thread getAndSet(Thread newValue) {
    return (Thread) VALUE_HANDLE.getAndSet(this, newValue);
  }

  /**
   * @return value with opaque semantics (bitwise atomicity only)
   */
  public Thread getOpaque() {
    return (Thread) VALUE_HANDLE.getOpaque(this);
  }

  /**
   * @param expectedValue expected current value
   * @param newValue new value
   * @return true if successful
   */
  public boolean compareAndSet(Thread expectedValue, Thread newValue) {
    return VALUE_HANDLE.compareAndSet(this, expectedValue, newValue);
  }
}
