package datadog.trace.api.iast;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class RealCallThrowable extends RuntimeException {
  private static final MethodHandle FILTER;

  static {
    try {
      FILTER =
          MethodHandles.lookup()
              .findStatic(
                  RealCallThrowable.class
                      .getClassLoader()
                      .loadClass("datadog.trace.util.stacktrace.StackUtils"),
                  "filterDatadog",
                  MethodType.methodType(Throwable.class, Throwable.class));
    } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public RealCallThrowable(Throwable cause) {
    super(cause);
  }

  public void rethrow() {
    Throwable cause;
    try {
      cause = (Throwable) FILTER.invoke(getCause());
    } catch (Throwable e) {
      cause = getCause();
    }
    RealCallThrowable.<RuntimeException>doRethrow(cause);
  }

  @SuppressWarnings("unchecked")
  public static <E extends Throwable> void doRethrow(Throwable e) throws E {
    throw (E) e;
  }
}
