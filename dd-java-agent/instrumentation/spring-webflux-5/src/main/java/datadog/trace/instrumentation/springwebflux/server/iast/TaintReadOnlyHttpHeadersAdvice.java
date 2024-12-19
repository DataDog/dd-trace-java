package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#get(Object) */
class TaintReadOnlyHttpHeadersAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(@Advice.Argument(0) Object headers, @Advice.Return Object retValue) {

    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || retValue == null) {
      return;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    module.taintObjectIfTainted(to, retValue, headers);
  }
}
