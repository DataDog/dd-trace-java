package datadog.trace.instrumentation.trace_annotation;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.Strings;
import datadog.trace.util.stacktrace.StackWalker;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;

public class SpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    StackTraceElement[] stackTrace =
        new Exception("\"SpanOriginAdvice.onExit\" trace").getStackTrace();
    AgentSpan span = AgentTracer.get().activeScope().span();
    StackTraceElement stackTraceElement = stackTrace[0];

    span.setTag(DDTags.DD_ENTRY_LOCATION_FILE, stackTraceElement.getClassName());
    span.setTag(DDTags.DD_ENTRY_METHOD, stackTraceElement.getMethodName());
    span.setTag(DDTags.DD_ENTRY_START_LINE, stackTraceElement.getLineNumber());

    List<String> params = new ArrayList();
    for (Class<?> parameterType : method.getParameterTypes()) {
      params.add(parameterType.getName());
    }
    span.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, Strings.join(",", params));
  }

  private static void attachMethodSignature(Method method, AgentSpan span) {
    List<String> params = new ArrayList();
    for (Class<?> parameterType : method.getParameterTypes()) {
      params.add(parameterType.getName());
    }
    span.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, Strings.join(",", params));
  }

  @Advice.OnMethodExit
  public static void onExit(@Advice.Origin final Method method) {
    AgentSpan span = AgentTracer.get().activeScope().span();
    StackTraceElement[] stackTrace =
        new Exception("\"SpanOriginAdvice.onExit\" trace").getStackTrace();
    span.setTag(DDTags.DD_ENTRY_END_LINE, stackTrace[0].getLineNumber());

    System.out.println("***********   span.getTags() = " + span.getTags());
  }

  private static int lineNumber(String className, String methodName) {
    // memoize this
    try {
      StackWalker walker = StackWalkerFactory.INSTANCE;
      System.out.println("walker = " + walker);
      return -1;
      //           return walker.walk(
      //                stream ->
      //                    -100
      /*stream
      .filter(
          element ->
              element.getClassName().equals(className)
              && element.getMethodName().equals(methodName))
      .map(StackTraceElement::getLineNumber)
      .findFirst()
      .orElse(-1)*/
      //        );
    } catch (Throwable e) {
      e.printStackTrace();
      return Integer.MIN_VALUE;
    }
  }
}
