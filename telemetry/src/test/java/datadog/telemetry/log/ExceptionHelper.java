package datadog.telemetry.log;

class ExceptionHelper {

  static class MutableException extends Exception {
    public MutableException(String message) {
      super(message, null, true, true);
    }
  }

  static void throwExceptionFromDatadogCode(String message) throws Exception {
    throw new Exception(message);
  }

  static void throwExceptionFromDatadogCodeWithoutStacktrace(String message) throws Exception {
    Exception ex = new MutableException(message);
    ex.setStackTrace(new StackTraceElement[0]);
    throw ex;
  }

  static boolean isDataDogOrJava(String stackLine) {
    int slashPosition = stackLine.indexOf('/');
    int startOfLine = (-1 != slashPosition ? slashPosition : stackLine.indexOf(" at ") + 3);
    String callSpec = stackLine.substring(startOfLine + 1, stackLine.length());
    for (String prefix : LogPeriodicAction.PACKAGE_LIST) {
      if (callSpec.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
