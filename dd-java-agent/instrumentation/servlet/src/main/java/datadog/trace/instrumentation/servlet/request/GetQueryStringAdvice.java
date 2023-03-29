package datadog.trace.instrumentation.servlet.request;

import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import net.bytebuddy.asm.Advice;

@IastAdvice.Source(SourceTypes.REQUEST_QUERY_STRING)
public class GetQueryStringAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void getQueryString(@Advice.Return final String value) {
    final WebModule module = InstrumentationBridge.WEB;
    if (module != null) {
      module.onQueryString(value);
    }
  }
}
