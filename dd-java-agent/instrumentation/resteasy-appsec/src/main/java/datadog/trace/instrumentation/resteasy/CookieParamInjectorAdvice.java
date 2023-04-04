package datadog.trace.instrumentation.resteasy;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.core.CookieParamInjector;

public class CookieParamInjectorAdvice {
  @Advice.OnMethodExit
  public static void onExit(
      @Advice.This CookieParamInjector self,
      @Advice.Return(readOnly = true) Object result,
      @Advice.FieldValue("paramName") String paramName) {
    if (result instanceof String || result instanceof Collection) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          if (result instanceof Collection) {
            Collection collection = (Collection) result;
            for (Object o : collection) {
              if (o instanceof String) {
                module.namedTaint(SourceTypes.REQUEST_COOKIE_VALUE, paramName, (String) o);
              }
            }
          } else {
            module.namedTaint(SourceTypes.REQUEST_COOKIE_VALUE, paramName, (String) result);
          }
        } catch (final Throwable e) {
          module.onUnexpectedException("CookieParamInjectorAdvice.onExit threw", e);
        }
      }
    }
  }
}
