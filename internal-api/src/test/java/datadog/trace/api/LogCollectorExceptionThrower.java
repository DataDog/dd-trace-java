package datadog.trace.api;

public class LogCollectorExceptionThrower {
  public static void throwException(String msg) throws Exception {
    throw new Exception(msg);
  }
}
