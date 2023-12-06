package datadog.trace.bootstrap.debugger;

public class CapturedContextHelper {

  public static void addArg(CapturedContext capturedContext, String name, String type, int value) {
    System.out.printf("addArg name=%s type=%s with value=%d%n", name, type, value);
    CapturedContext.CapturedValue capturedValue =
        CapturedContext.CapturedValue.of(name, type, value);
    capturedContext.addArguments(new CapturedContext.CapturedValue[] {capturedValue});
  }

  public static void addArg(
      CapturedContext capturedContext, String name, String type, Object value) {
    System.out.printf("addArg name=%s type=%s with value=%s%n", name, type, value.toString());
    CapturedContext.CapturedValue capturedValue =
        CapturedContext.CapturedValue.of(name, type, value);
    capturedContext.addArguments(new CapturedContext.CapturedValue[] {capturedValue});
  }

  public static void addLocal(
      CapturedContext capturedContext, String name, String type, int value) {
    System.out.printf("addLocal name=%s type=%s with value=%d%n", name, type, value);
    CapturedContext.CapturedValue capturedValue =
        CapturedContext.CapturedValue.of(name, type, value);
    capturedContext.addArguments(new CapturedContext.CapturedValue[] {capturedValue});
  }

  public static void addLocal(
      CapturedContext capturedContext, String name, String type, Object value) {
    System.out.printf("addLocal name=%s type=%s with value=%s%n", name, type, value.toString());
    CapturedContext.CapturedValue capturedValue =
        CapturedContext.CapturedValue.of(name, type, value);
    capturedContext.addLocals(new CapturedContext.CapturedValue[] {capturedValue});
  }

  public static void commit(CapturedContext capturedContext, String sourceFileName, int line) {
    System.out.println("jvmti commit");
    DebuggerContext.evalContextAndCommit(
        capturedContext, null, line, "jvmtiExceptionProbe|" + sourceFileName + ":" + line);
  }

  public static void addException(CapturedContext capturedContext, Throwable t) {
    System.out.printf("addException msg=%s%n", t.getMessage());
    capturedContext.addThrowable(t);
  }
}
