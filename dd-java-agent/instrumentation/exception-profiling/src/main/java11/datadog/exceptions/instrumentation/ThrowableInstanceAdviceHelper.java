package datadog.exceptions.instrumentation;

/**
 * A simple utility class to provide re-entrancy guard support
 */
public final class ThrowableInstanceAdviceHelper {
  private static final ThreadLocal<Boolean> enteredFlag = ThreadLocal.withInitial(() -> false);

  /**
   * Try to enter 'handler' code
   * @return {@literal true} if the handler hasn't been entered yet on this thread, {@literal false} otherwise
   */
  public static boolean enterHandler() {
    if (enteredFlag.get()) {
      return false;
    }
    enteredFlag.set(true);
    return true;
  }

  /**
   * Exit the 'handler' code.
   * Should be always called in finally block of the handler.
   */
  public static void exitHandler() {
    enteredFlag.remove();
  }
}
