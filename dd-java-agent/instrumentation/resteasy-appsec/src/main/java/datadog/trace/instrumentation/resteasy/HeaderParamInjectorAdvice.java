package datadog.trace.instrumentation.resteasy;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import net.bytebuddy.asm.Advice;

public class HeaderParamInjectorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_HEADER_VALUE)
  public static void onExit(
      @Advice.Return Object result, @Advice.FieldValue("paramName") String paramName) {
    if (result instanceof String || result instanceof Collection) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          if (result instanceof Collection) {
            Collection collection = (Collection) result;
            for (Object o : collection) {
              if (o instanceof String) {
                module.taint(SourceTypes.REQUEST_HEADER_VALUE, paramName, (String) o);
              }
            }
          } else {
            module.taint(SourceTypes.REQUEST_HEADER_VALUE, paramName, (String) result);
          }
        } catch (final Throwable e) {
          module.onUnexpectedException("HeaderParamInjectorAdvice.onExit threw", e);
        }
      }
    }
  }
}
