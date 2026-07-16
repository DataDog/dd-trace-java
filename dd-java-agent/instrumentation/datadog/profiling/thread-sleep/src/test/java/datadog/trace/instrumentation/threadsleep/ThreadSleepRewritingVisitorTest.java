// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.CAPTURE_FOR_SLEEP_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.FINISH_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.SLEEP_DURATION_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.SLEEP_JI_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.SLEEP_J_DESC;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.TASK_BLOCK_HELPER;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.THREAD_INTERNAL;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.TIME_UNIT_INTERNAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.Test;

/**
 * ASM-level shape tests for {@link ThreadSleepRewritingVisitor}. Verifies that every {@code
 * INVOKESTATIC Thread.sleep} call site is wrapped in a {@code captureForSleep} / {@code finish}
 * pair, that the original args are preserved, and that the protected region covers both the normal
 * and exceptional exit paths.
 */
class ThreadSleepRewritingVisitorTest {

  @Test
  void singleSleepJ_isWrappedWithCaptureAndFinishAndPreservesArg() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(SingleSleepJFixture.class, "doSleep");

    int sleepIdx = indexOfThreadSleep(insns, SLEEP_J_DESC);
    assertTrue(sleepIdx >= 0, "expected INVOKESTATIC Thread.sleep(J)V to be present");

    int captureIdx = indexOfInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC);
    assertTrue(
        captureIdx >= 0 && captureIdx < sleepIdx, "captureForSleep must precede Thread.sleep");

    int finishAfterSleep = nextInvokeStatic(insns, sleepIdx + 1, "finish", FINISH_DESC);
    assertTrue(
        finishAfterSleep > sleepIdx,
        "finish call must follow Thread.sleep on the normal exit path");

    // The wrapped form must also have a second finish on the exception-handler path so finish
    // runs when Thread.sleep throws InterruptedException.
    int finishCount = countInvokeStatic(insns, "finish", FINISH_DESC);
    assertEquals(
        2,
        finishCount,
        "expected two finish calls (normal exit + exception handler) per sleep site");

    // The original LSTORE/LLOAD round-trip is necessary so the long arg is preserved across the
    // injected captureForSleep call.
    assertTrue(countOpcode(insns, Opcodes.LSTORE) >= 1, "expected LSTORE for cached millis");
    assertTrue(countOpcode(insns, Opcodes.LLOAD) >= 1, "expected LLOAD to re-push millis");

    int aThrowCount = countOpcode(insns, Opcodes.ATHROW);
    assertTrue(
        aThrowCount >= 1,
        "expected ATHROW in synthetic exception handler to rethrow the caught Throwable");
  }

  @Test
  void singleSleepJI_isWrappedAndArgOrderPreserved() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(SingleSleepJIFixture.class, "doSleep");

    int sleepIdx = indexOfThreadSleep(insns, SLEEP_JI_DESC);
    assertTrue(sleepIdx >= 0, "expected INVOKESTATIC Thread.sleep(JI)V to be present");

    // The (JI)V overload requires ISTORE (top of stack: int) before LSTORE (next: long).
    assertTrue(countOpcode(insns, Opcodes.ISTORE) >= 1, "expected ISTORE for cached nanos arg");
    assertTrue(countOpcode(insns, Opcodes.LSTORE) >= 1, "expected LSTORE for cached millis arg");

    // Stack-rebuild before the sleep call: LLOAD then ILOAD.
    int lloadIdx = previousOpcode(insns, sleepIdx, Opcodes.LLOAD);
    int iloadIdx = previousOpcode(insns, sleepIdx, Opcodes.ILOAD);
    assertTrue(
        lloadIdx >= 0 && iloadIdx >= 0 && lloadIdx < iloadIdx,
        "LLOAD must precede ILOAD when rebuilding the (long, int) args");
  }

  @Test
  void multipleSleepSites_eachReceiveIndependentWrap() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(MultipleSleepFixture.class, "doWork");

    int sleepCount = countThreadSleep(insns);
    assertEquals(3, sleepCount, "fixture should have three Thread.sleep call sites");

    int captureCount = countInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC);
    int finishCount = countInvokeStatic(insns, "finish", FINISH_DESC);
    assertEquals(3, captureCount, "expected one captureForSleep per sleep site");
    assertEquals(
        6, finishCount, "expected two finish calls per sleep site (normal + handler) = 6 total");
  }

  @Test
  void otherInvokeStatic_isNotRewritten() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(OtherInvokeFixture.class, "doWork");
    assertEquals(
        0,
        countInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC),
        "non-Thread.sleep INVOKESTATIC sites must be left alone");
    assertEquals(
        0,
        countInvokeStatic(insns, "finish", FINISH_DESC),
        "non-Thread.sleep INVOKESTATIC sites must be left alone");
  }

  @Test
  void singleSleepDuration_isWrappedWithCaptureAndFinish() {
    // The fixture bytecode is generated with ASM directly rather than compiled from a Java
    // source inner class because Thread.sleep(Duration) was introduced in JDK 19; compiling a
    // source reference to it would break builds on JDK 8/11/17.
    List<InstructionRecord> insns = rewriteAndScan(sleepDurationFixtureBytes(), "doSleep");

    int sleepIdx = indexOfThreadSleep(insns, SLEEP_DURATION_DESC);
    assertTrue(sleepIdx >= 0, "expected INVOKESTATIC Thread.sleep(Duration)V to be present");

    int captureIdx = indexOfInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC);
    assertTrue(
        captureIdx >= 0 && captureIdx < sleepIdx, "captureForSleep must precede Thread.sleep");

    int finishAfterSleep = nextInvokeStatic(insns, sleepIdx + 1, "finish", FINISH_DESC);
    assertTrue(
        finishAfterSleep > sleepIdx,
        "finish call must follow Thread.sleep on the normal exit path");

    int finishCount = countInvokeStatic(insns, "finish", FINISH_DESC);
    assertEquals(
        2,
        finishCount,
        "expected two finish calls (normal exit + exception handler) per sleep site");

    // The Duration reference must be stashed in a local (ASTORE) and reloaded (ALOAD) so the
    // captureForSleep call does not drop it from the stack.
    assertTrue(countOpcode(insns, Opcodes.ASTORE) >= 1, "expected ASTORE for cached Duration ref");
    assertTrue(countOpcode(insns, Opcodes.ALOAD) >= 1, "expected ALOAD to re-push Duration ref");

    int aThrowCount = countOpcode(insns, Opcodes.ATHROW);
    assertTrue(
        aThrowCount >= 1,
        "expected ATHROW in synthetic exception handler to rethrow the caught Throwable");
  }

  @Test
  void classWithoutSleepCall_scanReturnsFalse() throws IOException {
    assertFalse(
        ThreadSleepScanner.scan(new ClassReader(classBytes(OtherInvokeFixture.class))),
        "scanner must not flag a class with no Thread.sleep call sites");
  }

  @Test
  void timeUnitSleep_isWrappedWithCaptureAndFinishAndPreservesReceiverAndArg() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(TimeUnitSleepFixture.class, "doSleep");

    int sleepIdx = indexOfTimeUnitSleep(insns);
    assertTrue(sleepIdx >= 0, "expected INVOKEVIRTUAL TimeUnit.sleep(J)V to be present");

    int captureIdx = indexOfInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC);
    assertTrue(
        captureIdx >= 0 && captureIdx < sleepIdx, "captureForSleep must precede TimeUnit.sleep");

    int aloadIdx = previousOpcode(insns, sleepIdx, Opcodes.ALOAD);
    int lloadIdx = previousOpcode(insns, sleepIdx, Opcodes.LLOAD);
    assertTrue(aloadIdx >= 0 && lloadIdx >= 0 && aloadIdx < lloadIdx);

    int finishCount = countInvokeStatic(insns, "finish", FINISH_DESC);
    assertEquals(
        2,
        finishCount,
        "expected two finish calls (normal exit + exception handler) per sleep site");
  }

  @Test
  void inheritedStaticSleepOwnedByThreadSubclassIsWrapped() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(ThreadSubclassSleepFixture.class, "doSleep");

    assertEquals(1, countInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC));
    assertEquals(2, countInvokeStatic(insns, "finish", FINISH_DESC));
  }

  @Test
  void unrelatedStaticSleepOwnerIsNotWrapped() {
    List<InstructionRecord> insns =
        rewriteAndScan(staticSleepOwnerFixtureBytes("java/lang/String"), "doSleep");

    assertEquals(0, countInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC));
    assertEquals(0, countInvokeStatic(insns, "finish", FINISH_DESC));
  }

  @Test
  void unresolvedStaticSleepOwnerIsNotWrapped() {
    List<InstructionRecord> insns =
        rewriteAndScan(staticSleepOwnerFixtureBytes("example/missing/ThreadLike"), "doSleep");

    assertEquals(0, countInvokeStatic(insns, "captureForSleep", CAPTURE_FOR_SLEEP_DESC));
    assertEquals(0, countInvokeStatic(insns, "finish", FINISH_DESC));
  }

  @Test
  void rewrittenLongAndLongIntSleepsExecuteNormally() throws Exception {
    Class<?> longFixture = loadRewritten(SingleSleepJFixture.class);
    Method longSleep = longFixture.getDeclaredMethod("doSleep", long.class);
    longSleep.setAccessible(true);
    longSleep.invoke(null, 1L);

    Class<?> longIntFixture = loadRewritten(SingleSleepJIFixture.class);
    Method longIntSleep = longIntFixture.getDeclaredMethod("doSleep", long.class, int.class);
    longIntSleep.setAccessible(true);
    longIntSleep.invoke(null, 0L, 1);
  }

  @Test
  void rewrittenTimeUnitSleepExecutesNormally() throws Exception {
    Class<?> fixture = loadRewritten(TimeUnitSleepFixture.class);
    Method sleep = fixture.getDeclaredMethod("doSleep", long.class);
    sleep.setAccessible(true);

    sleep.invoke(null, 1L);
  }

  @Test
  void rewrittenDurationSleepExecutesNormallyWhenAvailable() throws Exception {
    try {
      Thread.class.getMethod("sleep", Duration.class);
    } catch (NoSuchMethodException ignored) {
      assumeTrue(false, "Thread.sleep(Duration) requires JDK 19 or later");
    }

    Class<?> fixture =
        new ByteArrayClassLoader(getClass().getClassLoader())
            .define(rewrite(sleepDurationFixtureBytes()));
    Method sleep = fixture.getDeclaredMethod("doSleep", Duration.class);
    sleep.setAccessible(true);

    sleep.invoke(null, Duration.ofMillis(1L));
  }

  @Test
  void rewrittenSleepPreservesInterruptedException() throws Exception {
    Class<?> fixture = loadRewritten(SingleSleepJFixture.class);
    Method sleep = fixture.getDeclaredMethod("doSleep", long.class);
    sleep.setAccessible(true);
    Thread.currentThread().interrupt();
    try {
      InvocationTargetException expected =
          assertThrows(InvocationTargetException.class, () -> sleep.invoke(null, 10L));
      assertTrue(expected.getCause() instanceof InterruptedException);
    } finally {
      Thread.interrupted();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  @SuppressWarnings("unused")
  static final class SingleSleepJFixture {
    static void doSleep(long ms) throws InterruptedException {
      Thread.sleep(ms);
    }
  }

  @SuppressWarnings("unused")
  static final class SingleSleepJIFixture {
    static void doSleep(long ms, int nanos) throws InterruptedException {
      Thread.sleep(ms, nanos);
    }
  }

  @SuppressWarnings("unused")
  static final class MultipleSleepFixture {
    static void doWork() throws InterruptedException {
      Thread.sleep(10);
      Thread.sleep(20);
      Thread.sleep(30, 100);
    }
  }

  @SuppressWarnings("unused")
  static final class OtherInvokeFixture {
    static long doWork() {
      // Same shape (INVOKESTATIC with a long arg) as Thread.sleep but different owner/name —
      // must not be rewritten.
      return Math.abs(-42L);
    }
  }

  @SuppressWarnings("unused")
  static final class TimeUnitSleepFixture {
    static void doSleep(long timeout) throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(timeout);
    }
  }

  static final class ThreadSubclass extends Thread {}

  static final class ThreadSubclassSleepFixture {
    static void doSleep(long timeout) throws InterruptedException {
      ThreadSubclass.sleep(timeout);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Helpers (mirroring SynchronizedRewritingVisitorTest)
  // ------------------------------------------------------------------------------------------

  /**
   * Generates the bytecode for a class equivalent to:
   *
   * <pre>
   *   static void doSleep(java.time.Duration d) throws InterruptedException {
   *     Thread.sleep(d);
   *   }
   * </pre>
   *
   * using ASM directly so that the test compiles on JDK 8/11/17 even though {@code
   * Thread.sleep(Duration)} was only introduced in JDK 19.
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
        "datadog/trace/instrumentation/threadsleep/SleepDurationFixture",
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

  private static byte[] staticSleepOwnerFixtureBytes(String owner) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        "datadog/trace/instrumentation/threadsleep/StaticSleepOwnerFixture",
        null,
        "java/lang/Object",
        null);
    MethodVisitor method =
        writer.visitMethod(
            Opcodes.ACC_STATIC,
            "doSleep",
            "(J)V",
            null,
            new String[] {"java/lang/InterruptedException"});
    method.visitCode();
    method.visitVarInsn(Opcodes.LLOAD, 0);
    method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "sleep", SLEEP_J_DESC, false);
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(2, 2);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static List<InstructionRecord> rewriteAndScan(final Class<?> cls, final String method)
      throws IOException {
    byte[] rewritten = rewrite(classBytes(cls));
    return scanMethod(rewritten, method);
  }

  private static List<InstructionRecord> rewriteAndScan(
      final byte[] classBytes, final String method) {
    byte[] rewritten = rewrite(classBytes);
    return scanMethod(rewritten, method);
  }

  private static byte[] rewrite(final byte[] in) {
    ClassReader reader = new ClassReader(in);
    ClassWriter writer =
        new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
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
    ThreadSleepRewritingVisitor wrapper = new ThreadSleepRewritingVisitor();
    ClassVisitor cv =
        new ThreadSleepRewritingVisitor.ThreadSleepClassVisitor(
            writer, TypePool.Default.ofSystemLoader());
    reader.accept(cv, ClassReader.EXPAND_FRAMES);
    assertEquals(ClassWriter.COMPUTE_FRAMES, wrapper.mergeWriter(0) & ClassWriter.COMPUTE_FRAMES);
    assertEquals(ClassReader.EXPAND_FRAMES, wrapper.mergeReader(0) & ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }

  private static byte[] classBytes(final Class<?> cls) throws IOException {
    String resource = cls.getName().replace('.', '/') + ".class";
    try (InputStream in = cls.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Could not find class resource: " + resource);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static Class<?> loadRewritten(Class<?> fixture) throws IOException {
    return new ByteArrayClassLoader(fixture.getClassLoader()).define(rewrite(classBytes(fixture)));
  }

  private static final class ByteArrayClassLoader extends ClassLoader {
    private ByteArrayClassLoader(ClassLoader parent) {
      super(parent);
    }

    private Class<?> define(byte[] bytes) {
      return defineClass(null, bytes, 0, bytes.length);
    }
  }

  private static List<InstructionRecord> scanMethod(final byte[] bytes, final String methodName) {
    ClassReader reader = new ClassReader(bytes);
    List<InstructionRecord> result = new ArrayList<>();
    reader.accept(
        new ClassVisitor(OpenedClassReader.ASM_API) {
          @Override
          public MethodVisitor visitMethod(
              final int access,
              final String name,
              final String descriptor,
              final String signature,
              final String[] exceptions) {
            if (!name.equals(methodName)) {
              return null;
            }
            return new MethodVisitor(OpenedClassReader.ASM_API) {
              @Override
              public void visitInsn(final int opcode) {
                result.add(InstructionRecord.simple(opcode));
              }

              @Override
              public void visitVarInsn(final int opcode, final int var) {
                InstructionRecord r = InstructionRecord.simple(opcode);
                r.var = var;
                result.add(r);
              }

              @Override
              public void visitMethodInsn(
                  final int opcode,
                  final String owner,
                  final String name,
                  final String descriptor,
                  final boolean isInterface) {
                InstructionRecord r = InstructionRecord.simple(opcode);
                r.methodOwner = owner;
                r.methodName = name;
                r.methodDescriptor = descriptor;
                result.add(r);
              }
            };
          }
        },
        ClassReader.SKIP_FRAMES);
    return result;
  }

  private static int indexOfThreadSleep(final List<InstructionRecord> insns, final String desc) {
    for (int i = 0; i < insns.size(); i++) {
      InstructionRecord r = insns.get(i);
      if (r.opcode == Opcodes.INVOKESTATIC
          && THREAD_INTERNAL.equals(r.methodOwner)
          && "sleep".equals(r.methodName)
          && desc.equals(r.methodDescriptor)) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfTimeUnitSleep(final List<InstructionRecord> insns) {
    for (int i = 0; i < insns.size(); i++) {
      InstructionRecord r = insns.get(i);
      if (r.opcode == Opcodes.INVOKEVIRTUAL
          && TIME_UNIT_INTERNAL.equals(r.methodOwner)
          && "sleep".equals(r.methodName)
          && SLEEP_J_DESC.equals(r.methodDescriptor)) {
        return i;
      }
    }
    return -1;
  }

  private static int countThreadSleep(final List<InstructionRecord> insns) {
    int n = 0;
    for (InstructionRecord r : insns) {
      if (r.opcode == Opcodes.INVOKESTATIC
          && THREAD_INTERNAL.equals(r.methodOwner)
          && "sleep".equals(r.methodName)
          && (SLEEP_J_DESC.equals(r.methodDescriptor)
              || SLEEP_JI_DESC.equals(r.methodDescriptor)
              || SLEEP_DURATION_DESC.equals(r.methodDescriptor))) {
        n++;
      }
    }
    return n;
  }

  private static int indexOfInvokeStatic(
      final List<InstructionRecord> insns, final String name, final String desc) {
    for (int i = 0; i < insns.size(); i++) {
      InstructionRecord r = insns.get(i);
      if (r.opcode == Opcodes.INVOKESTATIC
          && TASK_BLOCK_HELPER.equals(r.methodOwner)
          && name.equals(r.methodName)
          && desc.equals(r.methodDescriptor)) {
        return i;
      }
    }
    return -1;
  }

  private static int nextInvokeStatic(
      final List<InstructionRecord> insns, final int from, final String name, final String desc) {
    for (int i = from; i < insns.size(); i++) {
      InstructionRecord r = insns.get(i);
      if (r.opcode == Opcodes.INVOKESTATIC
          && TASK_BLOCK_HELPER.equals(r.methodOwner)
          && name.equals(r.methodName)
          && desc.equals(r.methodDescriptor)) {
        return i;
      }
    }
    return -1;
  }

  private static int countInvokeStatic(
      final List<InstructionRecord> insns, final String name, final String desc) {
    int n = 0;
    for (InstructionRecord r : insns) {
      if (r.opcode == Opcodes.INVOKESTATIC
          && TASK_BLOCK_HELPER.equals(r.methodOwner)
          && name.equals(r.methodName)
          && desc.equals(r.methodDescriptor)) {
        n++;
      }
    }
    return n;
  }

  private static int countOpcode(final List<InstructionRecord> insns, final int opcode) {
    int n = 0;
    for (InstructionRecord r : insns) {
      if (r.opcode == opcode) n++;
    }
    return n;
  }

  private static int previousOpcode(
      final List<InstructionRecord> insns, final int from, final int opcode) {
    for (int i = from - 1; i >= 0; i--) {
      if (insns.get(i).opcode == opcode) {
        return i;
      }
    }
    return -1;
  }

  private static final class InstructionRecord {
    int opcode;
    int var;
    String methodOwner;
    String methodName;
    String methodDescriptor;

    static InstructionRecord simple(final int opcode) {
      InstructionRecord r = new InstructionRecord();
      r.opcode = opcode;
      return r;
    }
  }
}
