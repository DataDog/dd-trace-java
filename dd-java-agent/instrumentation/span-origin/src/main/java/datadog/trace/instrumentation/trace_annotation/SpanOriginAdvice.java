package datadog.trace.instrumentation.trace_annotation;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class SpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter() {
    System.out.println("SpanOriginAdvice.onEnter");
  }

  @Advice.OnMethodExit
  public static void onExit(@Advice.Origin final Method method) {

    System.out.println("*********** SpanOriginAdvice.onExit");

    AgentSpan span = AgentTracer.get().activeScope().span();
    System.out.println("span = " + span);

    String className = method.getDeclaringClass().getName();
    String methodName = method.getName();

    System.out.println("className = " + className);
    System.out.println("methodName = " + methodName);
    Integer lineNumber = -1; // lineNumber(className, methodName);

    span.setTag(DDTags.DD_ENTRY_LOCATION_FILE, className);
    span.setTag(DDTags.DD_ENTRY_METHOD, methodName);
    span.setTag(DDTags.DD_ENTRY_START_LINE, lineNumber);

    System.out.println("****************");
    System.out.println("****************");
    System.out.println("****************");
    System.out.println("***********   span.getTags() = " + span.getTags());
    System.out.println("****************");
    System.out.println("****************");
    System.out.println("****************");
  }

  /*
    private static Integer lineNumber(String className, String methodName) {
      // memoize this
      try {
        StackWalker walker = StackWalkerFactory.INSTANCE;
        System.out.println("walker = " + walker);
        return
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
      } catch (Throwable e) {
        e.printStackTrace();
        return Integer.MIN_VALUE;
      }
    }
  */
}
