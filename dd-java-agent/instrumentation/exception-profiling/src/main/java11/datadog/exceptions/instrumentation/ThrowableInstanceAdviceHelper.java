package datadog.exceptions.instrumentation;

public final class ThrowableInstanceAdviceHelper {
  private static final ThreadLocal<Boolean> enteredFlag = ThreadLocal.withInitial(() -> false);

  public static boolean enterHandler() {
    if (enteredFlag.get()) {
      return false;
    }
    enteredFlag.set(true);
    return true;
  }

  public static void exitHandler() {
    enteredFlag.remove();
  }
}
