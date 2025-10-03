package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Sink(VulnerabilityTypes.REFLECTION_INJECTION)
@CallSite(spi = IastCallSites.class)
public class ClassCallSite {

  @CallSite.Before("java.lang.Class java.lang.Class.forName(java.lang.String)")
  @CallSite.Before(
      "java.lang.Class java.lang.Class.forName(java.lang.String, boolean, java.lang.ClassLoader)")
  public static void beforeClassReflection(@CallSite.Argument(0) @Nonnull final String className) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onClassName(className);
      } catch (Throwable e) {
        module.onUnexpectedException("before class reflection threw", e);
      }
    }
  }

  @CallSite.Before(
      "java.lang.reflect.Method java.lang.Class.getMethod(java.lang.String, java.lang.Class[])")
  @CallSite.Before(
      "java.lang.reflect.Method java.lang.Class.getDeclaredMethod(java.lang.String, java.lang.Class[])")
  public static void beforeMethodReflection(
      @CallSite.This @Nonnull final Class<?> clazz,
      @CallSite.Argument(0) @Nonnull final String methodName,
      @CallSite.Argument(1) @Nullable final Class<?>... parameterTypes) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onMethodName(clazz, methodName, parameterTypes);
      } catch (Throwable e) {
        module.onUnexpectedException("before method reflection threw", e);
      }
    }
  }

  @CallSite.Before("java.lang.reflect.Field java.lang.Class.getField(java.lang.String)")
  @CallSite.Before("java.lang.reflect.Field java.lang.Class.getDeclaredField(java.lang.String)")
  public static void beforeFieldReflection(
      @CallSite.This @Nonnull final Class<?> clazz,
      @CallSite.Argument(0) @Nonnull final String fieldName) {
    final ReflectionInjectionModule module = InstrumentationBridge.REFLECTION_INJECTION;
    if (module != null) {
      try {
        module.onFieldName(clazz, fieldName);
      } catch (Throwable e) {
        module.onUnexpectedException("before field reflection threw", e);
      }
    }
  }
}
