package datadog.telemetry.log;

public class ExceptionHelper {
  public static void throwExceptionFromDatadogCode(String message) throws Exception {
    throw new Exception(message);
  }

  public static boolean isDataDogOrJava(String stackLine) {
    int startOfLine =
        (-1 != stackLine.indexOf('/') ? stackLine.indexOf('/') : stackLine.indexOf(" at ") + 3);
    String callSpec = stackLine.substring(startOfLine + 1, stackLine.length());
    return callSpec.startsWith("java.")
        || callSpec.startsWith("datadog.")
        || callSpec.startsWith("com.datadog.")
        || callSpec.startsWith("javax.");
  }

  public static Exception createException(String msg) {
    try {
      throw new Exception(msg);
    } catch (Exception e) {
      return e;
    }
  }
}
