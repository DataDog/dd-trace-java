package datadog.trace.bootstrap.debugger;

/** Stores information of a stacktrace's frame */
public class CapturedStackFrame {
  private final String fileName;
  private final String function;
  private final int lineNumber;

  public CapturedStackFrame(String fileName, String function, int lineNumber) {
    this.fileName = fileName;
    this.function = function;
    this.lineNumber = lineNumber;
  }

  public CapturedStackFrame(String function, int lineNumber) {
    this(null, function, lineNumber);
  }

  public static CapturedStackFrame from(StackTraceElement element) {
    return new CapturedStackFrame(
        element.getFileName(), getFunction(element), element.getLineNumber());
  }

  private static String getFunction(StackTraceElement element) {
    return element.getClassName() + "." + element.getMethodName();
  }

  public String getFileName() {
    return fileName;
  }

  public String getFunction() {
    return function;
  }

  public int getLineNumber() {
    return lineNumber;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CapturedStackFrame that = (CapturedStackFrame) o;
    return lineNumber == that.lineNumber
        && java.util.Objects.equals(fileName, that.fileName)
        && java.util.Objects.equals(function, that.function);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(fileName, function, lineNumber);
  }

  @Override
  public String toString() {
    return "CapturedStackFrame{"
        + "fileName='"
        + fileName
        + '\''
        + ", function='"
        + function
        + '\''
        + ", lineNumber="
        + lineNumber
        + '}';
  }
}
