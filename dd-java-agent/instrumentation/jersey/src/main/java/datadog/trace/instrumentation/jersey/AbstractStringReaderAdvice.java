package datadog.trace.instrumentation.jersey;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

public class AbstractStringReaderAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  public static void onExit(@Advice.Return(readOnly = true) Object result) {
    if (result instanceof String) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taint(SourceTypes.REQUEST_PARAMETER_VALUE, null, (String) result);
      }
    }
  }
}
