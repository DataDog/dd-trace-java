package datadog.trace.civisibility.coverage.instrumentation;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.Predicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public class CoverageClassTransformer implements ClassFileTransformer {

  private static final ClassLoader AGENT_CLASSLOADER =
      CoverageClassTransformer.class.getClassLoader();

  private final Predicate<String> instrumentationFilter;

  public CoverageClassTransformer(Predicate<String> instrumentationFilter) {
    this.instrumentationFilter = instrumentationFilter;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] source) {
    if (loader == null || loader == AGENT_CLASSLOADER) {
      return null; // skip bootstrap and agent classes
    }

    if (!instrumentationFilter.test(className)) {
      return null;
    }

    int majorVersion = ((source[6] & 0xFF) << 8) | (source[7] & 0xFF);
    if (majorVersion < 49) {
      return null; // skip classes compiled by Java older than 1.5
    }

    ClassReader reader = new ClassReader(source);
    ClassWriter writer = new ClassWriter(reader, 0);
    reader.accept(
        new CoverageClassVisitor(writer, instrumentationFilter), ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }
}
