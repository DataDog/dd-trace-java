package datadog.telemetry.log

class ExceptionHelper {
  static void throwExceptionFromDatadogCode(String message) throws Exception {
    throw new Exception(message)
  }

  static boolean isDataDogOrJava(String stackLine) {
    int startOfLine =
    (-1 != stackLine.indexOf('/') ? stackLine.indexOf('/') : stackLine.indexOf(" at ") + 3)
    String callSpec = stackLine.substring(startOfLine + 1, stackLine.length())
    return callSpec.startsWith("java.")
    || callSpec.startsWith("datadog.")
    || callSpec.startsWith("com.datadog.")
    || callSpec.startsWith("javax.")
  }
}