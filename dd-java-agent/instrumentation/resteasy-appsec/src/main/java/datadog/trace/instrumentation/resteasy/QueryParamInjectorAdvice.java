package datadog.trace.instrumentation.resteasy;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.core.QueryParamInjector;

public class QueryParamInjectorAdvice {
  @Advice.OnMethodExit
  public static void onExit(
      @Advice.This QueryParamInjector self,
      @Advice.Return(readOnly = true) Object result,
      @Advice.FieldValue("encodedName") String paramName) {
    if (result instanceof String || result instanceof Collection) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          if (result instanceof Collection) {
            Collection collection = (Collection) result;
            for (Object o : collection) {
              if (o instanceof String) {
                module.namedTaint(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, (String) o);
              }
            }
          } else {
            module.namedTaint(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, (String) result);
          }
        } catch (final Throwable e) {
          module.onUnexpectedException("QueryParamInjectorAdvice.onExit threw", e);
        }
      }
    }
  }
}
