package datadog.trace.instrumentation.span_origin;

import datadog.trace.bootstrap.debugger.spanorigin.SpanOriginInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    SpanOriginInfo.entry(AgentTracer.get().activeScope().span(), method);
  }
}
