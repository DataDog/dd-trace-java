package datadog.trace.agent.tooling.bytebuddy.memoize;

import datadog.trace.bootstrap.instrumentation.classloading.ClassDefining;
import net.bytebuddy.jar.asm.ClassReader;

/** Ensures superclasses and interfaces are loaded before classes that extend/implement them. */
final class PreloadHierarchy implements ClassDefining.Observer {
  private static final PreloadHierarchy PRELOADER = new PreloadHierarchy();

  static void observeClassDefinitions() {
    ClassDefining.observe(PRELOADER);
  }

  private static final int RECENTLY_CHECKED_MASK = (1 << 5) - 1;
  private final String[] recentlyChecked = new String[RECENTLY_CHECKED_MASK + 1];

  @Override
  public void beforeDefineClass(ClassLoader loader, byte[] bytecode, int offset, int length) {
    try {
      // check first byte matches the standard class header
      if (bytecode[offset] != (byte) 0xCA) {
        return; // ignore non-standard formats like J9 ROMs
      }
      // minimal parsing of bytecode to get name of superclass and any interfaces
      ClassReader cr = new ClassReader(bytecode, offset, length);
      String superName = cr.getSuperName();
      if (null != superName && !"java/lang/Object".equals(superName)) {
        preload(loader, superName);
      }
      for (String interfaceName : cr.getInterfaces()) {
        preload(loader, interfaceName);
      }
    } catch (Throwable ignore) {
      // stop preloading as soon as we encounter any issue
    }
  }

  /** Attempts to preload the named class using same class-loader as the original request. */
  private void preload(ClassLoader loader, String internalName) throws ClassNotFoundException {
    int slot = internalName.hashCode() & RECENTLY_CHECKED_MASK;
    if (!internalName.equals(recentlyChecked[slot])) {
      recentlyChecked[slot] = internalName;
      String name = internalName.replace('/', '.');
      // avoid preloading known uninteresting classes
      if (Memoizer.potentialMatch(name)) {
        loader.loadClass(name);
      }
    }
  }
}
