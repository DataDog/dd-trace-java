package datadog.trace.api.iast;

import java.lang.invoke.MethodHandle;

public class RealCallThrowable extends RuntimeException {
  private static final MethodHandle FILTER;

  static {
    //    try {
    //      FILTER =
    //          MethodHandles.lookup()
    //              .findStatic(
    //                  RealCallThrowable.class
    //                      .getClassLoader()
    //                      .loadClass("datadog.trace.util.stacktrace.StackUtils"),
    //                  "filterDatadog",
    //                  MethodType.methodType(Throwable.class, Throwable.class));
    //    } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
    //      throw new RuntimeException(e);
    //    }
    FILTER = null;
  }

  public RealCallThrowable(Throwable cause) {
    super(filter(cause));
  }

  private static Throwable filter(Throwable cause) {
    try {
      return (Throwable) FILTER.invoke(cause);
    } catch (Throwable e) {
      return cause;
    }
  }

  public void rethrow() {
    RealCallThrowable.<RuntimeException>doRethrow(getCause());
  }

  @SuppressWarnings("unchecked")
  public static <E extends Throwable> void doRethrow(Throwable e) throws E {
    throw (E) e;
  }
}
