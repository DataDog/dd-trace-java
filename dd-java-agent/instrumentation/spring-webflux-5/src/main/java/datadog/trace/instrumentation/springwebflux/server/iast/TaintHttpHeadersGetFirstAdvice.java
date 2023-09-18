package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#getFirst(String) */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintHttpHeadersGetFirstAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(@Advice.Argument(0) String arg, @Advice.Return String value) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || arg == null || value == null) {
      return;
    }
    module.taint(SourceTypes.REQUEST_HEADER_VALUE, arg, value);
  }
}
