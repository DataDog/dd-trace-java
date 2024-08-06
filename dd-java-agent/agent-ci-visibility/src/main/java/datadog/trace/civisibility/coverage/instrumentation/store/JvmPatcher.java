package datadog.trace.civisibility.coverage.instrumentation.store;

import datadog.trace.civisibility.config.JvmInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public class JvmPatcher {

  private static final String DD_TEMP_DIRECTORY_PREFIX = "dd-ci-vis-class-";

  private final CoreJvmClassReader coreJvmClassReader;
  private final JvmInfo jvm;

  public JvmPatcher(JvmInfo jvm) {
    this.coreJvmClassReader = new CoreJvmClassReader();
    this.jvm = jvm;
  }

  /**
   * Instruments core JVM classes:
   *
   * <ul>
   *   <li>{@link Thread} class by injecting a field that is used for coverage data storage
   * </ul>
   *
   * The instrumented bytecode is written to a temporary folder. The folder contains sub-folders
   * corresponding to packages, e.g. instrumented {@link Thread} class is stored in {@code
   * temporaryFolder/java/lang/Thread.class}
   *
   * @return the path to the folder where the instrumented classes are
   * @throws Exception If classes could not be instrumented
   */
  public Path createPatch() throws Exception {
    Path patchedClassesDir = Files.createTempDirectory(DD_TEMP_DIRECTORY_PREFIX);
    instrument(Thread.class, CoverageStoreFieldInjector::new, patchedClassesDir);
    return patchedClassesDir;
  }

  private void instrument(
      Class<?> c, Function<ClassWriter, ClassVisitor> classVisitor, Path targetDir)
      throws Exception {
    byte[] instrumentedBytecode =
        coreJvmClassReader.withClassStream(
            jvm,
            c.getName(),
            bytecodeStream -> {
              ClassReader cr = new ClassReader(bytecodeStream);
              ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
              cr.accept(classVisitor.apply(cw), ClassReader.EXPAND_FRAMES);
              return cw.toByteArray();
            });

    Path classPath = targetDir.resolve(c.getName().replace('.', '/') + ".class");
    Files.createDirectories(classPath.getParent());
    Files.write(classPath, instrumentedBytecode);
  }
}
