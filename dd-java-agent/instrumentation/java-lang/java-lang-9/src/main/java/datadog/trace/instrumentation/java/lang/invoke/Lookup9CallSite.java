package datadog.trace.instrumentation.java.lang.invoke;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.REFLECTION_INJECTION)
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isMajorJavaVersionAtLeast", "9"})
public class Lookup9CallSite {

  @CallSite.Before(
      "java.lang.Class java.lang.invoke.MethodHandles$Lookup.findClass(java.lang.String)")
  public static void beforeFindClass(@CallSite.Argument(0) @Nonnull final String className) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onClassName(className);
      } catch (Throwable e) {
        module.onUnexpectedException("beforeFindClass threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.lang.invoke.VarHandle java.lang.invoke.MethodHandles$Lookup.findStaticVarHandle(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.VarHandle java.lang.invoke.MethodHandles$Lookup.findVarHandle(java.lang.Class, java.lang.String, java.lang.Class)")
  public static void beforeFindVar(
      @CallSite.Argument(0) @Nonnull final Class<?> clazz,
      @CallSite.Argument(1) @Nonnull final String fieldName,
      @CallSite.Argument(2) @Nullable final Class<?> parameterType) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onFieldName(clazz, fieldName);
      } catch (Throwable e) {
        module.onUnexpectedException("beforeFindGetter threw", e);
      }
    }
  }
}
