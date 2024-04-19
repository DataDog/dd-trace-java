package datadog.trace.instrumentation.trace_annotation;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class SpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter() {
    System.out.println("SpanOriginAdvice.onEnter");
  }

  @Advice.OnMethodExit
  public static void onExit(
      @Advice.Enter final AgentScope scope, @Advice.Origin final Method method) {

    System.out.println("SpanOriginAdvice.onExit");
    System.out.println("scope = " + scope + ", method = " + method);

    /*
    AgentSpan span = scope.span();
    StackWalker walker = StackWalkerFactory.INSTANCE;

    String className = method.getDeclaringClass().getName();
    String methodName = method.getName();

    // memoize this
    Integer lineNumber =
        walker.walk(
            stream ->
                stream
                    .filter(
                        element ->
                            element.getClassName().equals(className)
                                && element.getMethodName().equals(methodName))
                    .map(StackTraceElement::getLineNumber)
                    .findFirst()
                    .orElse(-1));

    span.setTag(DDTags.DD_ENTRY_LOCATION_FILE, className);
    span.setTag(DDTags.DD_ENTRY_METHOD, method);
    span.setTag(DDTags.DD_ENTRY_START_LINE, lineNumber);
    */
  }
}
