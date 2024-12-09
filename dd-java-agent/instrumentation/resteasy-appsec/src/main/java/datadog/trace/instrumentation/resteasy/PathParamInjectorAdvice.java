package datadog.trace.instrumentation.resteasy;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.Collection;
import net.bytebuddy.asm.Advice;

public class PathParamInjectorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
  public static void onExit(
      @Advice.Return Object result, @Advice.FieldValue("paramName") String paramName) {
    if (result instanceof String || result instanceof Collection) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        if (result instanceof Collection) {
          Collection<?> collection = (Collection<?>) result;
          for (Object o : collection) {
            if (o instanceof String) {
              module.taintObject(to, o, SourceTypes.REQUEST_PATH_PARAMETER, paramName);
            }
          }
        } else {
          module.taintObject(to, result, SourceTypes.REQUEST_PATH_PARAMETER, paramName);
        }
      }
    }
  }
}
