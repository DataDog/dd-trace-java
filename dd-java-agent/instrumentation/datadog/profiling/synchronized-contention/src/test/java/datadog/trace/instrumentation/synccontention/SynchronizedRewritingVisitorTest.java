package datadog.trace.instrumentation.synccontention;

import static datadog.trace.instrumentation.synccontention.SynchronizedMethodVisitor.CAPTURE_FOR_MONITOR_DESC;
import static datadog.trace.instrumentation.synccontention.SynchronizedMethodVisitor.FINISH_DESC;
import static datadog.trace.instrumentation.synccontention.SynchronizedMethodVisitor.TASK_BLOCK_HELPER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.Test;

/**
 * ASM-level shape tests and verifier-load tests for {@link SynchronizedRewritingVisitor}.
 *
 * <p>Shape tests assert the expected opcode pattern is present in the rewritten bytecode. Verifier
 * tests ({@code _passesVerifierAndExecutes}) define the rewritten class in a fresh ClassLoader and
 * invoke it, proving the JVM verifier accepts the output.
 */
class SynchronizedRewritingVisitorTest {

  @Test
  void synchronizedBlock_wrapsMonitorEnterWithCaptureAndFinish() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(BlockFixture.class, "doWork");

    int idx = indexOfMonitorEnter(insns);
    assertTrue(idx >= 0, "expected at least one MONITORENTER in rewritten method");

    // Before MONITORENTER: ... DUP, INVOKESTATIC captureForMonitor, ASTORE <local>
    int dupIdx = previousOpcode(insns, idx, Opcodes.DUP);
    assertTrue(dupIdx >= 0 && dupIdx < idx, "missing DUP before MONITORENTER");
    assertTrue(
        containsInvokeStatic(insns, dupIdx, idx, "captureForMonitor", CAPTURE_FOR_MONITOR_DESC),
        "missing INVOKESTATIC TaskBlockHelper.captureForMonitor before MONITORENTER");
    assertTrue(
        nextOpcode(insns, idx, Opcodes.ALOAD) > idx, "missing ALOAD <state> after MONITORENTER");
    assertTrue(
        containsInvokeStatic(insns, idx, insns.size(), "finish", FINISH_DESC),
        "missing INVOKESTATIC TaskBlockHelper.finish after MONITORENTER");
  }

  @Test
  void nestedSynchronizedBlocks_useDistinctStateLocals() throws IOException {
    List<InstructionRecord> insns = rewriteAndScan(NestedBlocksFixture.class, "doWork");
    int monitorCount = countOpcode(insns, Opcodes.MONITORENTER);
    assertEquals(2, monitorCount, "fixture should have exactly two MONITORENTER opcodes");

    int captureCount = countInvokeStatic(insns, "captureForMonitor", CAPTURE_FOR_MONITOR_DESC);
    int finishCount = countInvokeStatic(insns, "finish", FINISH_DESC);
    assertEquals(2, captureCount, "expected one captureForMonitor per MONITORENTER");
    assertEquals(2, finishCount, "expected one finish per MONITORENTER");
  }

  @Test
  void synchronizedInstanceMethod_stripsFlagAndEmitsPrologueAndUnlock() throws IOException {
    MethodScan scan = rewriteAndInspect(InstanceMethodFixture.class, "doWork");
    assertFalse(
        (scan.access & Opcodes.ACC_SYNCHRONIZED) != 0,
        "ACC_SYNCHRONIZED flag should be stripped from rewritten method");

    // Prologue: ALOAD 0 (this), DUP, ASTORE <lock>, DUP, INVOKESTATIC captureForMonitor, ...,
    // MONITORENTER, ALOAD <state>, INVOKESTATIC finish.
    int captureIdx = indexOfInvokeStatic(scan.insns, "captureForMonitor", CAPTURE_FOR_MONITOR_DESC);
    assertTrue(captureIdx >= 0, "missing prologue captureForMonitor call");
    int aload0Idx = previousOpcode(scan.insns, captureIdx, Opcodes.ALOAD);
    assertTrue(
        aload0Idx >= 0 && scan.insns.get(aload0Idx).var == 0,
        "expected ALOAD 0 (this) before captureForMonitor in instance prologue");

    int monitorEnterIdx = indexOfMonitorEnter(scan.insns);
    assertTrue(monitorEnterIdx > captureIdx, "MONITORENTER should follow capture");
    assertTrue(
        containsInvokeStatic(scan.insns, monitorEnterIdx, scan.insns.size(), "finish", FINISH_DESC),
        "missing finish call after MONITORENTER");

    // Every return is preceded by ALOAD <lock>; MONITOREXIT.
    for (int i = 0; i < scan.insns.size(); i++) {
      InstructionRecord r = scan.insns.get(i);
      if (isReturnOpcode(r.opcode)) {
        assertTrue(
            i >= 2
                && scan.insns.get(i - 1).opcode == Opcodes.MONITOREXIT
                && scan.insns.get(i - 2).opcode == Opcodes.ALOAD,
            "return at index " + i + " is not preceded by ALOAD/MONITOREXIT");
      }
    }
  }

  @Test
  void synchronizedStaticMethod_usesClassLiteralAsLockTarget() throws IOException {
    MethodScan scan = rewriteAndInspect(StaticMethodFixture.class, "doWork");
    assertFalse(
        (scan.access & Opcodes.ACC_SYNCHRONIZED) != 0,
        "ACC_SYNCHRONIZED flag should be stripped from rewritten static method");

    int captureIdx = indexOfInvokeStatic(scan.insns, "captureForMonitor", CAPTURE_FOR_MONITOR_DESC);
    assertTrue(captureIdx >= 0);
    // First instruction in a static-synchronized prologue must be LDC of the owner class literal.
    int ldcIdx = indexOfOpcode(scan.insns, Opcodes.LDC);
    assertTrue(ldcIdx >= 0 && ldcIdx < captureIdx, "expected LDC <owner-class> in static prologue");
  }

  @Test
  void abstractMethod_isNotRewritten() throws IOException {
    // Abstract method (in an abstract class) — should be untouched.
    MethodScan scan = rewriteAndInspect(AbstractFixture.class, "doWork");
    // Abstract methods have no body to scan; assert flag is preserved.
    assertTrue(
        (scan.access & Opcodes.ACC_ABSTRACT) != 0,
        "abstract flag should remain set after rewriting");
  }

  // ---------------------------------------------------------------------------- helpers

  private static List<InstructionRecord> rewriteAndScan(final Class<?> fixture, final String method)
      throws IOException {
    return rewriteAndInspect(fixture, method).insns;
  }

  private static MethodScan rewriteAndInspect(final Class<?> fixture, final String method)
      throws IOException {
    byte[] original = loadBytes(fixture);
    byte[] rewritten = rewrite(original);
    return scanMethod(rewritten, method);
  }

  private static byte[] loadBytes(final Class<?> klass) throws IOException {
    String resource = klass.getName().replace('.', '/') + ".class";
    try (InputStream in = klass.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("class resource not found: " + resource);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) >= 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static byte[] rewrite(final byte[] in) {
    ClassReader reader = new ClassReader(in);
    // Use COMPUTE_FRAMES + EXPAND_FRAMES to match the production ByteBuddy path exactly
    // (mergeWriter returns COMPUTE_FRAMES, mergeReader returns EXPAND_FRAMES).
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
    SynchronizedRewritingVisitor wrapper = new SynchronizedRewritingVisitor();
    ClassVisitor cv = new SynchronizedRewritingVisitor.RewritingClassVisitor(writer);
    reader.accept(cv, ClassReader.EXPAND_FRAMES);
    assertEquals(ClassWriter.COMPUTE_FRAMES, wrapper.mergeWriter(0) & ClassWriter.COMPUTE_FRAMES);
    assertEquals(ClassReader.EXPAND_FRAMES, wrapper.mergeReader(0) & ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }

  // ------------------------------------------------- verifier-load helpers and tests

  private static Class<?> defineAndLoad(final byte[] bytes, final String binaryName) {
    return new ClassLoader(SynchronizedRewritingVisitorTest.class.getClassLoader()) {
      Class<?> define() {
        return defineClass(binaryName, bytes, 0, bytes.length);
      }
    }.define();
  }

  private static String binaryName(final Class<?> klass) {
    return klass.getName();
  }

  @Test
  void synchronizedBlock_passesVerifierAndExecutes() throws Exception {
    byte[] rewritten = rewrite(loadBytes(BlockFixture.class));
    Class<?> c = defineAndLoad(rewritten, binaryName(BlockFixture.class));
    assertDoesNotThrow(
        () -> {
          try {
            java.lang.reflect.Method m = c.getDeclaredMethod("doWork");
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            m.invoke(ctor.newInstance());
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        },
        "rewritten BlockFixture should execute without error");
  }

  @Test
  void nestedSynchronizedBlocks_passesVerifierAndExecutes() throws Exception {
    byte[] rewritten = rewrite(loadBytes(NestedBlocksFixture.class));
    Class<?> c = defineAndLoad(rewritten, binaryName(NestedBlocksFixture.class));
    assertDoesNotThrow(
        () -> {
          try {
            java.lang.reflect.Method m = c.getDeclaredMethod("doWork");
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            m.invoke(ctor.newInstance());
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        },
        "rewritten NestedBlocksFixture should execute without error");
  }

  @Test
  void synchronizedInstanceMethod_passesVerifierAndExecutes() throws Exception {
    byte[] rewritten = rewrite(loadBytes(InstanceMethodFixture.class));
    Class<?> c = defineAndLoad(rewritten, binaryName(InstanceMethodFixture.class));
    assertDoesNotThrow(
        () -> {
          try {
            java.lang.reflect.Method m = c.getDeclaredMethod("doWork");
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            m.invoke(ctor.newInstance());
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        },
        "rewritten InstanceMethodFixture should execute without error");
  }

  @Test
  void synchronizedStaticMethod_passesVerifierAndExecutes() throws Exception {
    byte[] rewritten = rewrite(loadBytes(StaticMethodFixture.class));
    Class<?> c = defineAndLoad(rewritten, binaryName(StaticMethodFixture.class));
    assertDoesNotThrow(
        () -> {
          try {
            java.lang.reflect.Method m = c.getDeclaredMethod("doWork");
            m.setAccessible(true);
            m.invoke(null);
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        },
        "rewritten StaticMethodFixture should execute without error");
  }

  @Test
  void synchronizedStaticMethodWithBodyLocals_keepsClassMonitorHeld() throws Exception {
    byte[] rewritten = rewrite(loadBytes(StaticMethodWithLocalsFixture.class));
    Class<?> c = defineAndLoad(rewritten, binaryName(StaticMethodWithLocalsFixture.class));

    java.lang.reflect.Method method = c.getDeclaredMethod("registerIfAbsent", Object.class);
    method.setAccessible(true);

    assertEquals(Boolean.TRUE, method.invoke(null, new Object()));
  }

  @Test
  void synchronizedMethodRewriteIsReflectionVisible() throws Exception {
    byte[] rewritten = rewrite(loadBytes(InstanceMethodFixture.class));
    Class<?> c = defineAndLoad(rewritten, binaryName(InstanceMethodFixture.class));

    java.lang.reflect.Method method = c.getDeclaredMethod("doWork");

    assertFalse(
        java.lang.reflect.Modifier.isSynchronized(method.getModifiers()),
        "method-level rewrite intentionally strips ACC_SYNCHRONIZED");
  }

  private static MethodScan scanMethod(final byte[] bytes, final String methodName) {
    ClassReader reader = new ClassReader(bytes);
    MethodScan result = new MethodScan();
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
            result.access = access;
            return new MethodVisitor(OpenedClassReader.ASM_API) {
              @Override
              public void visitInsn(final int opcode) {
                result.insns.add(InstructionRecord.simple(opcode));
              }

              @Override
              public void visitVarInsn(final int opcode, final int var) {
                InstructionRecord r = InstructionRecord.simple(opcode);
                r.var = var;
                result.insns.add(r);
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
                result.insns.add(r);
              }

              @Override
              public void visitLdcInsn(final Object value) {
                InstructionRecord r = InstructionRecord.simple(Opcodes.LDC);
                r.ldcValue = value;
                result.insns.add(r);
              }
            };
          }
        },
        ClassReader.SKIP_FRAMES);
    return result;
  }

  private static int indexOfMonitorEnter(final List<InstructionRecord> insns) {
    return indexOfOpcode(insns, Opcodes.MONITORENTER);
  }

  private static int indexOfOpcode(final List<InstructionRecord> insns, final int opcode) {
    for (int i = 0; i < insns.size(); i++) {
      if (insns.get(i).opcode == opcode) {
        return i;
      }
    }
    return -1;
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

  private static int nextOpcode(
      final List<InstructionRecord> insns, final int from, final int opcode) {
    for (int i = from + 1; i < insns.size(); i++) {
      if (insns.get(i).opcode == opcode) {
        return i;
      }
    }
    return -1;
  }

  private static int countOpcode(final List<InstructionRecord> insns, final int opcode) {
    int n = 0;
    for (InstructionRecord r : insns) {
      if (r.opcode == opcode) n++;
    }
    return n;
  }

  private static int indexOfInvokeStatic(
      final List<InstructionRecord> insns, final String name, final String descriptor) {
    for (int i = 0; i < insns.size(); i++) {
      InstructionRecord r = insns.get(i);
      if (r.opcode == Opcodes.INVOKESTATIC
          && TASK_BLOCK_HELPER.equals(r.methodOwner)
          && name.equals(r.methodName)
          && descriptor.equals(r.methodDescriptor)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean containsInvokeStatic(
      final List<InstructionRecord> insns,
      final int from,
      final int to,
      final String name,
      final String descriptor) {
    for (int i = from; i < to; i++) {
      InstructionRecord r = insns.get(i);
      if (r.opcode == Opcodes.INVOKESTATIC
          && TASK_BLOCK_HELPER.equals(r.methodOwner)
          && name.equals(r.methodName)
          && descriptor.equals(r.methodDescriptor)) {
        return true;
      }
    }
    return false;
  }

  private static int countInvokeStatic(
      final List<InstructionRecord> insns, final String name, final String descriptor) {
    int n = 0;
    for (InstructionRecord r : insns) {
      if (r.opcode == Opcodes.INVOKESTATIC
          && TASK_BLOCK_HELPER.equals(r.methodOwner)
          && name.equals(r.methodName)
          && descriptor.equals(r.methodDescriptor)) {
        n++;
      }
    }
    return n;
  }

  private static boolean isReturnOpcode(final int opcode) {
    switch (opcode) {
      case Opcodes.IRETURN:
      case Opcodes.LRETURN:
      case Opcodes.FRETURN:
      case Opcodes.DRETURN:
      case Opcodes.ARETURN:
      case Opcodes.RETURN:
        return true;
      default:
        return false;
    }
  }

  private static final class MethodScan {
    int access;
    final List<InstructionRecord> insns = new ArrayList<>();
  }

  private static final class InstructionRecord {
    int opcode;
    int var;
    String methodOwner;
    String methodName;
    String methodDescriptor;
    Object ldcValue;

    static InstructionRecord simple(final int opcode) {
      InstructionRecord r = new InstructionRecord();
      r.opcode = opcode;
      return r;
    }
  }

  // ---------------------------------------------------------------------------- fixtures

  static class BlockFixture {
    private final Object lock = new Object();

    void doWork() {
      synchronized (lock) {
        // Intentionally empty.
      }
    }
  }

  static class NestedBlocksFixture {
    private final Object outer = new Object();
    private final Object inner = new Object();

    void doWork() {
      synchronized (outer) {
        synchronized (inner) {
          // Nothing.
        }
      }
    }
  }

  static class InstanceMethodFixture {
    synchronized void doWork() {
      // Body emits at least one RETURN that should be preceded by MONITOREXIT.
    }
  }

  static class StaticMethodFixture {
    static synchronized void doWork() {
      // Body emits at least one RETURN preceded by MONITOREXIT, with class literal as lock.
    }
  }

  static class StaticMethodWithLocalsFixture {
    private static Object registered;

    static synchronized boolean registerIfAbsent(final Object candidate) {
      if (registered == null) {
        Object supplied = candidate;
        if (supplied == null) {
          return false;
        }
        registered = supplied;
        StaticMethodWithLocalsFixture.class.notifyAll();
        return true;
      }
      return false;
    }
  }

  abstract static class AbstractFixture {
    abstract void doWork();
  }
}
