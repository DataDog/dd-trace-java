package datadog.trace.bootstrap.debugger;

public enum MethodLocation {
  DEFAULT,
  ENTRY,
  EXIT;

  public static boolean isSame(MethodLocation methodLocation, MethodLocation evaluateAt) {
    if (methodLocation == MethodLocation.DEFAULT) {
      // line probe, no evaluation of probe's evaluateAt
      // MethodLocation.DEFAULT is used for line probe when evaluating the context
      return true;
    }
    if (methodLocation == MethodLocation.ENTRY) {
      return evaluateAt == MethodLocation.DEFAULT || evaluateAt == MethodLocation.ENTRY;
    }
    return methodLocation == evaluateAt;
  }
}
