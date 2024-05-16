package datadog.trace.instrumentation.span_origin;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.span_origin.EntrySpanOriginInfo;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    EntrySpanOriginInfo.apply(method, AgentTracer.get().activeScope().span());
  }
}
