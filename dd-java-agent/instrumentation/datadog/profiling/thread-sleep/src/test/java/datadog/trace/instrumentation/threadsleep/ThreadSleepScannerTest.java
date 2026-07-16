// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

class ThreadSleepScannerTest {

  @Test
  void instrumentationEntrypoint_isPublicForAgentClassloaderAccess() throws NoSuchMethodException {
    assertTrue(Modifier.isPublic(ThreadSleepScanner.class.getModifiers()));
    Method method =
        ThreadSleepScanner.class.getDeclaredMethod(
            "containsThreadSleepCallSite", ClassLoader.class, TypeDescription.class);
    assertTrue(Modifier.isPublic(method.getModifiers()));
  }

  // ---------------------------------------------------------------------------------
  // Positive cases: scan() must return true
  // ---------------------------------------------------------------------------------

  @Test
  void threadSleepJ_detected() throws IOException {
    assertTrue(
        ThreadSleepScanner.scan(
            classReader(ThreadSleepRewritingVisitorTest.SingleSleepJFixture.class)));
  }

  @Test
  void threadSleepJI_detected() throws IOException {
    assertTrue(
        ThreadSleepScanner.scan(
            classReader(ThreadSleepRewritingVisitorTest.SingleSleepJIFixture.class)));
  }

  @Test
  void threadSleepDuration_detected() {
    assertTrue(ThreadSleepScanner.scan(new ClassReader(sleepDurationFixtureBytes())));
  }

  @Test
  void timeUnitSleep_detected() throws IOException {
    assertTrue(
        ThreadSleepScanner.scan(
            classReader(ThreadSleepRewritingVisitorTest.TimeUnitSleepFixture.class)));
  }

  @Test
  void inheritedStaticSleepCandidateDetectedForVisitorResolution() throws IOException {
    assertTrue(
        ThreadSleepScanner.scan(
            classReader(ThreadSleepRewritingVisitorTest.ThreadSubclassSleepFixture.class)));
  }

  @Test
  void multipleSleepSites_detected() throws IOException {
    assertTrue(
        ThreadSleepScanner.scan(
            classReader(ThreadSleepRewritingVisitorTest.MultipleSleepFixture.class)));
  }

  // ---------------------------------------------------------------------------------
  // Negative case: scan() must return false
  // ---------------------------------------------------------------------------------

  @Test
  void noSleepCall_notDetected() throws IOException {
    assertFalse(
        ThreadSleepScanner.scan(
            classReader(ThreadSleepRewritingVisitorTest.OtherInvokeFixture.class)));
  }

  // ---------------------------------------------------------------------------------
  // Fail-open cases: containsThreadSleepCallSite() must return true
  // ---------------------------------------------------------------------------------

  @Test
  void nullClassLoader_returnsTrue() {
    assertTrue(
        ThreadSleepScanner.containsThreadSleepCallSite(
            null, TypeDescription.ForLoadedType.of(Object.class)));
  }

  @Test
  void resourceNotFound_returnsTrue() {
    ClassLoader emptyLoader =
        new ClassLoader() {
          @Override
          public InputStream getResourceAsStream(String name) {
            return null;
          }
        };
    assertTrue(
        ThreadSleepScanner.containsThreadSleepCallSite(
            emptyLoader, TypeDescription.ForLoadedType.of(Object.class)));
  }

  @Test
  void ioExceptionOnRead_returnsTrue() {
    ClassLoader failingLoader =
        new ClassLoader() {
          @Override
          public InputStream getResourceAsStream(String name) {
            return new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("simulated I/O error");
              }
            };
          }
        };
    assertTrue(
        ThreadSleepScanner.containsThreadSleepCallSite(
            failingLoader, TypeDescription.ForLoadedType.of(Object.class)));
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private static ClassReader classReader(Class<?> clazz) throws IOException {
    String resource = clazz.getName().replace('.', '/') + ".class";
    try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, "Could not load test fixture: " + clazz.getName());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return new ClassReader(out.toByteArray());
    }
  }

  /**
   * Generates bytecode for a class with a single {@code Thread.sleep(java.time.Duration)} call site
   * using ASM directly, so the test compiles on JDK 8/11/17 (the method was added in JDK 19).
   */
  private static byte[] sleepDurationFixtureBytes() {
    ClassWriter cw =
        new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
          @Override
          protected String getCommonSuperClass(final String type1, final String type2) {
            if (type1.equals(type2)) return type1;
            try {
              return super.getCommonSuperClass(type1, type2);
            } catch (Exception ignored) {
              return "java/lang/Object";
            }
          }
        };
    cw.visit(
        Opcodes.V11,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        "datadog/trace/instrumentation/threadsleep/SleepDurationScannerFixture",
        null,
        "java/lang/Object",
        null);
    MethodVisitor mv =
        cw.visitMethod(
            Opcodes.ACC_STATIC,
            "doSleep",
            "(Ljava/time/Duration;)V",
            null,
            new String[] {"java/lang/InterruptedException"});
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(Ljava/time/Duration;)V", false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }
}
