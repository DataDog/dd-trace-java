package datadog.trace.instrumentation.span_origin;

import datadog.trace.util.Strings;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class LineInfo {
  public final String className;

  public final int startLineNumber;

  public final String fileName;

  public int endLineNumber = -1;

  public final String methodName;

  public final String signature;

  public LineInfo(Method method, StackTraceElement element) {
    className = element.getClassName();
    fileName = element.getFileName();
    methodName = element.getMethodName();
    startLineNumber = element.getLineNumber();
    List<String> params = new ArrayList<>();
    for (Class<?> parameterType : method.getParameterTypes()) {
      params.add(parameterType.getName());
    }

    signature = Strings.join(",", params);
  }

  public int endLineNumber(Method method) {
    if (endLineNumber == -1) {
      StackTraceElement element =
          StackWalkerFactory.INSTANCE.walk(new FindFirstStackTraceElement(method));
      endLineNumber = element.getLineNumber();
    }
    return endLineNumber;
  }
}
