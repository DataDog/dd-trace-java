package datadog.trace.util;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.AnnotatedElement;
import java.util.Set;

/** Use standard API to work with JPMS modules on Java9+. */
@SuppressWarnings("Since15")
public final class JDK9ModuleAccess {

  /** Retrieves a class-loader's unnamed module. */
  public static AnnotatedElement getUnnamedModule(ClassLoader cl) {
    return cl.getUnnamedModule();
  }

  /** Returns {@code true} if the first module can read the second module. */
  public static boolean canRead(AnnotatedElement module, AnnotatedElement anotherModule) {
    return ((java.lang.Module) module).canRead((java.lang.Module) anotherModule);
  }

  /** Adds extra module reads to the given module. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void addModuleReads(
      Instrumentation inst, AnnotatedElement module, Set<AnnotatedElement> extraReads) {
    inst.redefineModule(
        (java.lang.Module) module,
        (Set) extraReads,
        emptyMap(),
        emptyMap(),
        emptySet(),
        emptyMap());
  }
}
