package datadog.trace.instrumentation.span_origin;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    StackTraceElement[] stackTrace =
        new Exception("\"EntrySpanOriginAdvice.onEnter\" trace").getStackTrace();
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

  @Advice.OnMethodExit
  public static void onExit() {
    AgentSpan span = AgentTracer.get().activeScope().span();
    StackTraceElement[] stackTrace =
        new Exception("\"EntrySpanOriginAdvice.onExit\" trace").getStackTrace();
    span.setTag(DDTags.DD_ENTRY_END_LINE, stackTrace[0].getLineNumber());
    System.out.println(">>>>>>  span.getTags() = " + span.getTags());
  }
}
