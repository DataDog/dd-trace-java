package datadog.trace.instrumentation.codeorigin;

import datadog.trace.bootstrap.debugger.spanorigin.CodeOriginInfo;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(@Advice.Origin final Method method) {
    CodeOriginInfo.entry(method);
  }
}
