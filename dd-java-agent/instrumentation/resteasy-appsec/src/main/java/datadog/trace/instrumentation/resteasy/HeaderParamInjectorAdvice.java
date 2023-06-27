package datadog.trace.instrumentation.resteasy;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import java.util.Collection;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.core.HeaderParamInjector;

public class HeaderParamInjectorAdvice {
  @Advice.OnMethodExit
  @Source(SourceTypes.REQUEST_HEADER_VALUE_STRING)
  public static void onExit(
      @Advice.This HeaderParamInjector self,
      @Advice.Return(readOnly = true) Object result,
      @Advice.FieldValue("paramName") String paramName) {
    if (result instanceof String || result instanceof Collection) {
      final WebModule module = InstrumentationBridge.WEB;

      if (module != null) {
        try {
          if (result instanceof Collection) {
            Collection collection = (Collection) result;
            for (Object o : collection) {
              if (o instanceof String) {
                module.onHeaderValue(paramName, (String) o);
              }
            }
          } else {
            module.onHeaderValue(paramName, (String) result);
          }
        } catch (final Throwable e) {
          module.onUnexpectedException("HeaderParamInjectorAdvice.onExit threw", e);
        }
      }
    }
  }
}
