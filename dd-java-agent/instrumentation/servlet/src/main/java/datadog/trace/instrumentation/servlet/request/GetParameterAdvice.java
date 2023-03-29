package datadog.trace.instrumentation.servlet.request;

import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import net.bytebuddy.asm.Advice;

@IastAdvice.Source(SourceTypes.REQUEST_PARAMETER_VALUE_STRING)
public class GetParameterAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void getParameter(
      @Advice.Argument(0) final String name, @Advice.Return final String value) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      module.onParameterValue(name, value);
    }
  }
}
