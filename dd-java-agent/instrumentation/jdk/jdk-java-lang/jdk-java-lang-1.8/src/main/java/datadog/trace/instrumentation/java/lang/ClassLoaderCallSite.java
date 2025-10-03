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
public class ClassLoaderCallSite {

  @CallSite.Before("java.lang.Class java.lang.ClassLoader.loadClass(java.lang.String)")
  public static void beforeLoadClass(@CallSite.Argument(0) @Nonnull final String className) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onClassName(className);
      } catch (Throwable e) {
        module.onUnexpectedException("before beforeLoadClass threw", e);
      }
    }
  }
}
