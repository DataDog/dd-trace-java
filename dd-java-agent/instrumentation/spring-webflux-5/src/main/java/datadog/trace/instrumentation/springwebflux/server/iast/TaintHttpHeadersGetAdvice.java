package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import java.util.Locale;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#get(Object) */
@RequiresRequestContext(RequestContextSlot.IAST)
class TaintHttpHeadersGetAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(@Advice.Argument(0) Object arg, @Advice.Return List<String> values) {
    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || values == null) {
      return;
    }
    if (!(arg instanceof String)) {
      return;
    }
    String lc = ((String) arg).toLowerCase(Locale.ROOT);
    for (String value : values) {
      module.taint(SourceTypes.REQUEST_HEADER_VALUE, lc, value);
    }
  }
}
