package datadog.trace.bootstrap.debugger.spanorigin;

public class LineInfo {
  public final String className;

  public final int lineNumber;

  public final String fileName;

  public final String methodName;

  public String signature;

  public LineInfo(StackTraceElement element) {
    className = element.getClassName();
    fileName = element.getFileName();
    methodName = element.getMethodName();
    lineNumber = element.getLineNumber();
  }
}
