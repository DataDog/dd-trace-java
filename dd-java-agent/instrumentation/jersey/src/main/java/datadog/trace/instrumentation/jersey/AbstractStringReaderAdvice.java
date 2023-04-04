package datadog.trace.instrumentation.jersey3;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

public class AbstractStringReaderAdvice {
  @Advice.OnMethodExit
  public static void onExit(@Advice.Return(readOnly = true) Object result) {
    if (result instanceof String) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taint(SourceTypes.REQUEST_PARAMETER_VALUE, (String) result);
        } catch (final Throwable e) {
          module.onUnexpectedException("AbstractStringReaderAdvice.exit threw", e);
        }
      }
    }
  }
}
