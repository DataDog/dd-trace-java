package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import net.bytebuddy.asm.Advice;

/** @see org.springframework.http.HttpHeaders#getFirst(String) */
class TaintHttpHeadersGetFirstAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void after(
      @Advice.This Object self, @Advice.Argument(0) String arg, @Advice.Return String value) {

    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module == null || arg == null || value == null) {
      return;
    }
    final TaintedObjects to = IastContext.Provider.taintedObjects();
    module.taintObjectIfTainted(to, value, self, SourceTypes.REQUEST_HEADER_VALUE, arg);
  }
}
