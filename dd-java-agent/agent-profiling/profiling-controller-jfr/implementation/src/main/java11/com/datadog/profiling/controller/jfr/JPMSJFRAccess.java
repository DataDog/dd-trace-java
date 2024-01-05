package com.datadog.profiling.controller.jfr;

import datadog.trace.api.Platform;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
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

  private final Class<?> jvmClass;
  private final Class<?> repositoryClass;
  private final Class<?> safePathClass;

  private final MethodHandle setStackDepthMH;
  private final MethodHandle setRepositoryBaseMH;

  public JPMSJFRAccess(Instrumentation inst) throws Exception {
    patchModuleAccess(inst);

    jvmClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
    repositoryClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.Repository");
    safePathClass =
        JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.SecuritySupport$SafePath");
    setStackDepthMH = setStackDepthMethodHandle();
    setRepositoryBaseMH = setRepositoryBaseMethodHandle();
  }

  private MethodHandle setStackDepthMethodHandle()
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    Method m = jvmClass.getMethod("setStackDepth", int.class);
    m.setAccessible(true);
    MethodHandle mh = MethodHandles.publicLookup().unreflect(m);
    if (!Modifier.isStatic(m.getModifiers())) {
      // instance method - need to call JVM.getJVM() and bind the instance
      Object jvm = jvmClass.getMethod("getJVM").invoke(null);
      mh = mh.bindTo(jvm);
    }
    return mh;
  }

  private MethodHandle setRepositoryBaseMethodHandle()
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    // the modules are patched in `patchModuleAccess` to allow reflective access to the internal
    // classes
    Method m = repositoryClass.getMethod("getRepository");
    m.setAccessible(true);
    Object repository = m.invoke(null);

    m = repositoryClass.getMethod("setBasePath", safePathClass);
    m.setAccessible(true);
    MethodHandle mh = MethodHandles.publicLookup().unreflect(m);
    mh = mh.bindTo(repository);
    return mh;
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

  @Override
  public boolean setBaseLocation(String location) {
    try {
      Object safePath = safePathClass.getConstructor(Path.class).newInstance(Paths.get(location));
      setRepositoryBaseMH.invoke(safePath);
      return true;
    } catch (Throwable throwable) {
      log.warn("Unable to set JFR repository base location", throwable);
    }
    return false;
  }
}
