package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#getFirst(String) */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintHttpHeadersGetFirstAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE_STRING)
  public static void after(@Advice.Argument(0) String arg, @Advice.Return String value) {
    WebModule module = InstrumentationBridge.WEB;
    if (module == null || arg == null || value == null) {
      return;
    }
    module.onHeaderValue(arg, value);
  }
}
