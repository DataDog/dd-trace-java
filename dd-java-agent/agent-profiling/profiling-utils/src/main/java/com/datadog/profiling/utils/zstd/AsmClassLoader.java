package com.datadog.profiling.utils.zstd;

/** ClassLoader subclass for defining ASM-generated classes at runtime. */
final class AsmClassLoader extends ClassLoader {
  static {
    ClassLoader.registerAsParallelCapable();
  }

  AsmClassLoader(ClassLoader parent) {
    super(parent);
  }

  Class<?> defineClass(String name, byte[] bytecode) {
    return defineClass(name, bytecode, 0, bytecode.length);
  }
}
