package datadog.telemetry.log

class ExceptionHelper {
  static void throwExceptionFromDatadogCode(String message) throws Exception {
    throw new Exception(message)
  }

  static boolean isDataDogOrJava(String stackLine) {
    int slashPosition = stackLine.indexOf('/')
    int startOfLine = (-1 != slashPosition ? slashPosition : stackLine.indexOf(" at ") + 3)
    String callSpec = stackLine.substring(startOfLine + 1, stackLine.length())
    for (String prefix : LogPeriodicAction.PACKAGE_LIST) {
      if (callSpec.startsWith(prefix)) {
        return true
      }
    }
    return false
  }
}
