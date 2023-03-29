package datadog.trace.instrumentation.servlet.request;

import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import net.bytebuddy.asm.Advice;

@IastAdvice.Source(SourceTypes.REQUEST_HEADER_VALUE_STRING)
public class GetHeaderAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void getHeader(
      @Advice.Argument(0) final String name, @Advice.Return final String value) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      module.onHeaderValue(name, value);
    }
  }
}
