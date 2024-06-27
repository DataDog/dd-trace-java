package datadog.trace.instrumentation.span_origin;

import com.datadog.debugger.spanorigin.SpanOriginInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    SpanOriginInfo.entry(method, AgentTracer.get().activeScope().span());
  }
}
