package com.datadog.profiling.controller.jfr;

import datadog.trace.api.Platform;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JPMSJFRAccess extends JFRAccess {
  private static final Logger log = LoggerFactory.getLogger(JPMSJFRAccess.class);

  public static final class FactoryImpl implements JFRAccess.Factory {
    private static final Logger log = LoggerFactory.getLogger(FactoryImpl.class);

    @Override
    public JFRAccess create(Instrumentation inst) {
      if (!Platform.isJ9() && Platform.isJavaVersionAtLeast(9)) {
        try {
          return new JPMSJFRAccess(inst);
        } catch (Exception e) {
          log.debug("Unable to obtain JFR internal access", e);
        }
      }
      return null;
    }
  }

  private final MethodHandle setStackDepthMH;

  public JPMSJFRAccess(Instrumentation inst) throws Exception {
    patchModuleAccess(inst);

    Class<?> jvmClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
    Method m = jvmClass.getMethod("setStackDepth", int.class);
    m.setAccessible(true);
    MethodHandle mh = MethodHandles.publicLookup().unreflect(m);
    if (!Modifier.isStatic(m.getModifiers())) {
      // instance method - need to call JVM.getJVM() and bind the instance
      Object jvm = jvmClass.getMethod("getJVM").invoke(null);
      mh = mh.bindTo(jvm);
    }
    setStackDepthMH = mh;
  }

  private static void patchModuleAccess(Instrumentation inst) {
    Module unnamedModule = JFRAccess.class.getClassLoader().getUnnamedModule();
    Module targetModule = Event.class.getModule();

    Map<String, Set<Module>> extraOpens = Map.of("jdk.jfr.internal", Set.of(unnamedModule));

    // Redefine the module
    inst.redefineModule(
        targetModule,
        Collections.emptySet(),
        extraOpens,
        extraOpens,
        Collections.emptySet(),
        Collections.emptyMap());
  }

  @Override
  public boolean setStackDepth(int depth) {
    try {
      setStackDepthMH.invokeExact(depth);
      return true;
    } catch (Throwable throwable) {
      log.warn("Unable to set JFR stack depth", throwable);
    }
    return false;
  }
}
