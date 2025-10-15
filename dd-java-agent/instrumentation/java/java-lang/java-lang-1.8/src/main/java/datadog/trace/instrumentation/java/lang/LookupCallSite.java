package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.REFLECTION_INJECTION)
@CallSite(spi = IastCallSites.class)
public class LookupCallSite {

  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findSetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findStaticSetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findGetter(java.lang.Class, java.lang.String, java.lang.Class)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findStaticGetter(java.lang.Class, java.lang.String, java.lang.Class)")
  public static void beforeFindField(
      @CallSite.Argument(0) @Nonnull final Class<?> clazz,
      @CallSite.Argument(1) @Nonnull final String fieldName,
      @CallSite.Argument(2) @Nullable final Class<?> parameterType) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onFieldName(clazz, fieldName);
      } catch (Throwable e) {
        module.onUnexpectedException("beforeFindField threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findStatic(java.lang.Class, java.lang.String, java.lang.invoke.MethodType)")
  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findVirtual(java.lang.Class, java.lang.String, java.lang.invoke.MethodType)")
  public static void beforeMethod(
      @CallSite.Argument(0) @Nonnull final Class<?> clazz,
      @CallSite.Argument(1) @Nonnull final String methodName,
      @CallSite.Argument(2) @Nullable final MethodType methodType) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onMethodName(
            clazz, methodName, methodType != null ? methodType.parameterArray() : null);
      } catch (Throwable e) {
        module.onUnexpectedException("beforeMethod threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.findSpecial(java.lang.Class, java.lang.String, java.lang.invoke.MethodType, java.lang.Class)")
  public static void beforeSpecial(
      @CallSite.Argument(0) @Nonnull final Class<?> clazz,
      @CallSite.Argument(1) @Nonnull final String methodName,
      @CallSite.Argument(2) @Nullable final MethodType methodType,
      @CallSite.Argument(3) @Nullable final Class<?> specialCaller) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onMethodName(
            clazz, methodName, methodType != null ? methodType.parameterArray() : null);
      } catch (Throwable e) {
        module.onUnexpectedException("beforeSpecial threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.lang.invoke.MethodHandle java.lang.invoke.MethodHandles$Lookup.bind(java.lang.Object, java.lang.String, java.lang.invoke.MethodType)")
  public static void beforeBind(
      @CallSite.Argument(0) @Nonnull final Object obj,
      @CallSite.Argument(1) @Nonnull final String methodName,
      @CallSite.Argument(2) @Nullable final MethodType methodType) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onMethodName(
            obj.getClass(), methodName, methodType != null ? methodType.parameterArray() : null);
      } catch (Throwable e) {
        module.onUnexpectedException("beforeBind threw", e);
      }
    }
  }
}
