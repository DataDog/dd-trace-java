package datadog.trace.instrumentation.jersey;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import net.bytebuddy.asm.Advice;

public class AbstractStringReaderAdvice {
  @Advice.OnMethodExit
  @Source(SourceTypes.REQUEST_PARAMETER_VALUE_STRING)
  public static void onExit(@Advice.Return(readOnly = true) Object result) {
    if (result instanceof String) {
      final WebModule module = InstrumentationBridge.WEB;

      if (module != null) {
        try {
          module.onParameterValue(null, (String) result);
        } catch (final Throwable e) {
          module.onUnexpectedException("AbstractStringReaderAdvice.exit threw", e);
        }
      }
    }
  }
}
