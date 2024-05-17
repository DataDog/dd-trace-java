package datadog.trace.bootstrap.instrumentation.span_origin;

import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class LineInfo {
  public final String className;

  public final int lineNumber;

  public final String fileName;

  public final String methodName;

  public final String signature;

  public LineInfo(Method method, StackTraceElement element) {
    className = element.getClassName();
    fileName = element.getFileName();
    methodName = element.getMethodName();
    lineNumber = element.getLineNumber();
    List<String> params = new ArrayList<>();
    for (Class<?> parameterType : method.getParameterTypes()) {
      params.add(parameterType.getName());
    }

    signature = Strings.join(",", params);
  }
}
