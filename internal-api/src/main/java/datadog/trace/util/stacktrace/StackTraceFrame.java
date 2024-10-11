package datadog.trace.util.stacktrace;

import javax.annotation.Nullable;

public class StackTraceFrame {

  // Unsigned integer: index of the stack frame (0 = top of stack)
  private final int id;
  // Raw stack frame
  @Nullable private final String text;
  @Nullable private final String file;
  private final int line;
  @Nullable private final String class_name;
  @Nullable private final String function;

  public StackTraceFrame(final int id, final StackTraceElement element) {
    this.id = id;
    this.text = element.toString();
    this.file = element.getFileName();
    this.line = element.getLineNumber();
    this.class_name = element.getClassName();
    this.function = element.getMethodName();
  }

  public int getId() {
    return id;
  }

  @Nullable
  public String getText() {
    return text;
  }

  @Nullable
  public String getFile() {
    return file;
  }

  public int getLine() {
    return line;
  }

  @Nullable
  public String getClass_name() {
    return class_name;
  }

  @Nullable
  public String getFunction() {
    return function;
  }
}
