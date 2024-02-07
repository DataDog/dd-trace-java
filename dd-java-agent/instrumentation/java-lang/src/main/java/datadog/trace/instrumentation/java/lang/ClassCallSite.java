package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import javax.annotation.Nonnull;

@Sink(VulnerabilityTypes.REFLECTION_INJECTION)
@CallSite(spi = IastCallSites.class)
public class ClassCallSite {

  @CallSite.Before("java.lang.Class java.lang.Class.forName(java.lang.String)")
  @CallSite.Before(
      "java.lang.Class java.lang.Class.forName(java.lang.String, boolean, java.lang.ClassLoader)")
  @CallSite.Before(
      "java.lang.reflect.Method java.lang.Class.getMethod(java.lang.String, java.lang.Class[])")
  @CallSite.Before(
      "java.lang.reflect.Method java.lang.Class.getDeclaredMethod(java.lang.String, java.lang.Class[])")
  public static void beforeReflection(@CallSite.AllArguments @Nonnull final Object[] params) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onReflection((String) params[0]);
      } catch (Throwable e) {
        module.onUnexpectedException("before reflection threw", e);
      }
    }
  }
}
