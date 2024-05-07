package datadog.trace.instrumentation.span_origin;

import static datadog.trace.instrumentation.span_origin.EntrySpanOriginInfo.*;
import static datadog.trace.instrumentation.span_origin.EntrySpanOriginInfo.start;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    start(method).applyStart(AgentTracer.get().activeScope().span());
  }

  @Advice.OnMethodExit
  public static void onExit(@Advice.Origin final Method method) {
    end(method).applyEnd(AgentTracer.get().activeScope().span(), method);
  }
}
