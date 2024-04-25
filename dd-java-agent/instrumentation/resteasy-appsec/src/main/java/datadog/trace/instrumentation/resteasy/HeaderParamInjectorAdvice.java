package datadog.trace.instrumentation.resteasy;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import net.bytebuddy.asm.Advice;

@RequiresRequestContext(RequestContextSlot.IAST)
public class HeaderParamInjectorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void onExit(
      @Advice.Return Object result,
      @Advice.FieldValue("paramName") String paramName,
      @ActiveRequestContext RequestContext reqCtx) {
    if (result instanceof String || result instanceof Collection) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        if (result instanceof Collection) {
          Collection<?> collection = (Collection<?>) result;
          for (Object o : collection) {
            if (o instanceof String) {
              module.taintString(ctx, (String) o, SourceTypes.REQUEST_HEADER_VALUE, paramName);
            }
          }
        } else {
          module.taintString(ctx, (String) result, SourceTypes.REQUEST_HEADER_VALUE, paramName);
        }
      }
    }
  }
}
