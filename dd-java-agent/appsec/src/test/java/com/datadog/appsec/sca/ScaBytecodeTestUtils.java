package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;

final class ScaBytecodeTestUtils {

  private ScaBytecodeTestUtils() {}

  static byte[] bytecodeOf(Class<?> clazz) throws Exception {
    String path = clazz.getName().replace('.', '/') + ".class";
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(is, "Cannot load bytecode for " + clazz.getName());
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int n;
      while ((n = is.read(chunk)) != -1) {
        buf.write(chunk, 0, n);
      }
      return buf.toByteArray();
    }
  }

  static Class<?> loadModified(byte[] bytecode) {
    return new ClassLoader(ScaBytecodeTestUtils.class.getClassLoader()) {
      Class<?> define() {
        return defineClass(null, bytecode, 0, bytecode.length);
      }
    }.define();
  }

  static byte[] bytecodeWithoutDebugInfo(Class<?> clazz) throws Exception {
    ClassReader cr = new ClassReader(bytecodeOf(clazz));
    ClassWriter cw = new ClassWriter(0);
    cr.accept(cw, ClassReader.SKIP_DEBUG);
    return cw.toByteArray();
  }
}
