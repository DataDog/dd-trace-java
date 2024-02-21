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
public class LookupCallSite {

  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.bind(java.lang.Object, java.lang.String, java.lang.invoke.MethodType)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findGetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findSetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findSpecial(java.lang.Class, java.lang.String, java.lang.invoke.MethodType, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findStatic(java.lang.Class, java.lang.String, java.lang.invoke.MethodType)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findStaticGetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findStaticSetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findVirtual(java.lang.Class, java.lang.String, java.lang.invoke.MethodType)")
  public static void beforeLookupMethod(@CallSite.Argument(1) @Nonnull final String className) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onLookupMethod(className);
      } catch (Throwable e) {
        module.onUnexpectedException("before beforeLookupMethod threw", e);
      }
    }
  }
}
