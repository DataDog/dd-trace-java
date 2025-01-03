package datadog.trace.agent.tooling;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.function.Function;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/** Shades advice bytecode by applying a shading function to all references. */
public final class AdviceShader extends Remapper {
  private final DDCache<String, String> cache = DDCaches.newFixedSizeCache(64);
  private final Function<String, String> shading;

  public static AdviceShader with(Function<String, String> shading) {
    return shading != null ? new AdviceShader(shading) : null;
  }

  AdviceShader(Function<String, String> shading) {
    this.shading = shading;
  }

  /** Applies shading before calling the given {@link ClassVisitor}. */
  public ClassVisitor shade(ClassVisitor cv) {
    return new ClassRemapper(cv, this);
  }

  /** Returns the result of shading the given bytecode. */
  public byte[] shade(byte[] bytecode) {
    ClassReader cr = new ClassReader(bytecode);
    ClassWriter cw = new ClassWriter(null, 0);
    cr.accept(shade(cw), 0);
    return cw.toByteArray();
  }

  @Override
  public String map(String name) {
    return cache.computeIfAbsent(name, shading);
  }
}
