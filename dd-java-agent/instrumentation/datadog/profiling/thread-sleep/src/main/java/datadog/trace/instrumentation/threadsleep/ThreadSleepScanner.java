// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import java.io.InputStream;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Scans class bytecode to determine whether a class contains at least one {@code Thread.sleep} or
 * {@code TimeUnit.sleep} call site, without triggering the full {@code COMPUTE_FRAMES} analysis.
 *
 * <p>Used by {@link ThreadSleepProfilingInstrumentation} to avoid attaching {@link
 * ThreadSleepRewritingVisitor} (and its {@code COMPUTE_FRAMES} cost) to classes that contain no
 * sleep call sites.
 *
 * <p>Fails open (returns {@code true}) on any error so the transformation still runs when bytes
 * cannot be read — preserving the pre-change safety guarantee.
 */
public final class ThreadSleepScanner {

  private ThreadSleepScanner() {}

  /**
   * Returns {@code true} if the class identified by {@code typeDescription} contains at least one
   * {@code Thread.sleep} or {@code TimeUnit.sleep} call site, {@code false} if it provably does
   * not.
   */
  public static boolean containsThreadSleepCallSite(
      ClassLoader classLoader, TypeDescription typeDescription) {
    if (classLoader == null) {
      // Bootstrap classloader — type matcher already excludes java.* but guard defensively.
      return true;
    }
    String resource = typeDescription.getInternalName() + ".class";
    try (InputStream is = classLoader.getResourceAsStream(resource)) {
      if (is == null) {
        // Runtime-generated class (dynamic proxy, ASM-generated) — no bytes on classpath.
        return true;
      }
      return scan(new ClassReader(is));
    } catch (Exception e) {
      return true;
    }
  }

  /** Package-private for unit testing. */
  static boolean scan(ClassReader classReader) {
    SleepDetector detector = new SleepDetector();
    classReader.accept(detector, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    return detector.found;
  }

  private static final class SleepDetector extends ClassVisitor {

    boolean found = false;

    SleepDetector() {
      super(OpenedClassReader.ASM_API);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String sig, String[] exceptions) {
      if (found) {
        return null; // short-circuit: skip remaining methods entirely
      }
      return new MethodVisitor(OpenedClassReader.ASM_API) {
        @Override
        public void visitMethodInsn(
            int opcode, String owner, String mName, String mDesc, boolean itf) {
          if (found) return;
          found = ThreadSleepCallSiteMatcher.isPotentiallySupported(opcode, owner, mName, mDesc);
        }
      };
    }
  }
}
