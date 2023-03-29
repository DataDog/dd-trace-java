package datadog.trace.instrumentation.servlet.request;

import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@IastAdvice.Source(SourceTypes.REQUEST_BODY_STRING)
public class GetInputStreamAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void getInputStream(@Advice.Return final Object value) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      module.taint(SourceTypes.REQUEST_BODY, value);
    }
  }
}
