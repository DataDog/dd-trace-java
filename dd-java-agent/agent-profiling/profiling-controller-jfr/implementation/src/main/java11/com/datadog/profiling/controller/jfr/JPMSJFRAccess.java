package com.datadog.profiling.controller.jfr;

import datadog.environment.JavaVirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
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
      if (!JavaVirtualMachine.isJ9() && JavaVirtualMachine.isJavaVersionAtLeast(9)) {
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

  // TODO consider refactoring to make these private static final
  private final MethodHandle setStackDepthMH;
  private final MethodHandle setRepositoryBaseMH;

  private final MethodHandle counterTimeMH;
  private final MethodHandle getTimeConversionFactorMH;

  private final Field evenWriterPositionField;
  private final MethodHandle getPositionFieldHolder;

  public JPMSJFRAccess(Instrumentation inst) throws Exception {
    patchModuleAccess(inst);

    jvmClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
    repositoryClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.Repository");
    safePathClass = safePathClass();
    Object jvm = getJvm();
    setStackDepthMH = getJvmMethodHandle(jvm, "setStackDepth", int.class);
    setRepositoryBaseMH = setRepositoryBaseMethodHandle();
    counterTimeMH = getJvmMethodHandle(jvm, "counterTime");
    getTimeConversionFactorMH = getJvmMethodHandle(jvm, "getTimeConversionFactor");
    getPositionFieldHolder = getJvmMethodHandle(jvm, "getEventWriter");

    Class<?> eventWriterClass = null;
    try {
      eventWriterClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.EventWriter");
    } catch (Throwable ignored) {
      // in JDK ~21 the package has changed
      eventWriterClass = JFRAccess.class.getClassLoader().loadClass("jdk.jfr.internal.event.EventWriter");
    }
    evenWriterPositionField = eventWriterClass.getDeclaredField("currentPosition");
    evenWriterPositionField.setAccessible(true);
  }

  private static Class<?> safePathClass() {
    try {
      return JFRAccess.class
          .getClassLoader()
          .loadClass("jdk.jfr.internal.SecuritySupport$SafePath");
    } catch (ClassNotFoundException e) {
      return Path.class; // no SafePath with SecurityManager gone
    }
  }

  private Object getJvm() {
    try {
      return jvmClass.getMethod("getJVM").invoke(null);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
    }
    return null;
  }

  private MethodHandle getJvmMethodHandle(Object jvm, String method, Class... args)
      throws NoSuchMethodException, IllegalAccessException {
    Method m = jvmClass.getMethod(method, args);
    m.setAccessible(true);
    return unreflectAndBind(m, jvm);
  }

  private static MethodHandle unreflectAndBind(Method method, Object jvm)
      throws IllegalAccessException {
    MethodHandle mh = MethodHandles.publicLookup().unreflect(method);
    if (!Modifier.isStatic(method.getModifiers())) {
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
    if (inst == null) {
      // used in testing; we don't have instrumentation and will patch the module access in the test
      // task
      return;
    }
    Module unnamedModule = JFRAccess.class.getClassLoader().getUnnamedModule();
    Module targetModule = Event.class.getModule();

    Map<String, Set<Module>> extraOpens = Map.of("jdk.jfr.internal", Set.of(unnamedModule), "jdk.jfr.internal.event", Set.of(unnamedModule));

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
      Object safePath =
          Path.class.isAssignableFrom(safePathClass)
              ? Paths.get(location)
              : safePathClass.getConstructor(Path.class).newInstance(Paths.get(location));
      setRepositoryBaseMH.invoke(safePath);
      return true;
    } catch (Throwable throwable) {
      log.warn("Unable to set JFR repository base location", throwable);
    }
    return false;
  }

  @Override
  public long timestamp() {
    try {
      return (long) counterTimeMH.invokeExact();
    } catch (Throwable t) {
      log.debug("Unable to get TSC from JFR", t);
    }
    return super.timestamp();
  }

  @Override
  public double toNanosConversionFactor() {
    try {
      return (double) getTimeConversionFactorMH.invokeExact();
    } catch (Throwable t) {
      log.debug("Unable to get time conversion factor from JFR", t);
    }
    return super.toNanosConversionFactor();
  }

  @Override
  public long getThreadWriterPosition() {
    try {
      Object eventWriter = getPositionFieldHolder.invoke();
      if (eventWriter != null) {
        return  (long) evenWriterPositionField.get(eventWriter);
      }
    } catch (Throwable e) {
      log.debug("Unable to get thread writer position from JFR", e);
    }
    return -1; // return a constant and invalid value
  }
}
