package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.List;
import java.util.Locale;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#get(Object) */
class TaintHttpHeadersGetAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(
      @Advice.This Object self,
      @Advice.Argument(0) Object arg,
      @Advice.Return List<String> values) {

    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || values == null || values.isEmpty()) {
      return;
    }
    if (!(arg instanceof String)) {
      return;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    String lc = ((String) arg).toLowerCase(Locale.ROOT);
    for (String value : values) {
      module.taintObjectIfTainted(to, value, self, SourceTypes.REQUEST_HEADER_VALUE, lc);
    }
  }
}
