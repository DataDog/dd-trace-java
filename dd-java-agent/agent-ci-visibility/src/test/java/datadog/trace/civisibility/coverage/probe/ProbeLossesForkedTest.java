package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.V1_8;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Classes and methods left without probes are counted and named. Forked, since the last test uses
 * up the process's class ids.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProbeLossesForkedTest {

  private static final String PACKAGE = "datadog/trace/civisibility/coverage/probe/";

  /**
   * Branches in the large method: its code fits the size limit, but not with a probe per branch.
   */
  private static final int BRANCHES = 9000;

  @Test
  @Order(1)
  void countsAMethodTooLargeForProbes() throws Exception {
    String name = PACKAGE + "HugeMethod";
    int before = ProbeLosses.methodCount();
    GeneratedClassLoader loader = new GeneratedClassLoader();

    byte[] instrumented = ProbeClassTransformer.instrument(generate(name, true), loader, true);

    assertNotNull(instrumented, "the small method still gets probes");
    assertEquals(before + 1, ProbeLosses.methodCount());
    assertTrue(
        losses().contains("method " + name + ".run(I)I: " + ProbeClassTransformer.TOO_LARGE),
        losses());
    Class<?> generated = loader.define(name.replace('/', '.'), instrumented);
    assertEquals(BRANCHES, generated.getMethod("run", int.class).invoke(null, 1));
    assertEquals(1, generated.getMethod("small").invoke(null));
  }

  @Test
  @Order(2)
  void countsAClassThatFailsToTransform() throws IOException {
    String name = PACKAGE + "Broken";
    int before = ProbeLosses.classCount();
    byte[] broken = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 0, 9, 1, 2};

    byte[] transformed =
        new ProbeClassTransformer(className -> true)
            .transform(new GeneratedClassLoader(), name, null, null, broken);

    assertNull(transformed);
    assertEquals(before + 1, ProbeLosses.classCount());
    assertTrue(losses().contains("class " + name + ": inserting probes failed with "), losses());
  }

  @Test
  @Order(3)
  void countsClassesBeyondTheClassLimit() throws IOException {
    String name = PACKAGE + "PastTheLimit";
    int before = ProbeLosses.classCount();
    while (ProbeRecorder.reserveClassId() >= 0) {
      // uses up the class ids
    }

    assertNull(
        ProbeClassTransformer.instrument(generate(name, false), new GeneratedClassLoader(), true));
    assertEquals(before + 1, ProbeLosses.classCount());
    assertTrue(
        losses().contains("class " + name + ": " + ProbeClassTransformer.CLASS_LIMIT), losses());
    assertTrue(losses().startsWith(ProbeLosses.classCount() + "\tlost: classes without probes\n"));
  }

  private static String losses() throws IOException {
    StringWriter writer = new StringWriter();
    ProbeLosses.writeTo(writer);
    return writer.toString();
  }

  /**
   * A class with {@code static int small()}, which returns 1, and, if asked for, {@code static int
   * run(int x)}, which adds 1 per branch when {@code x} is not 0, one line per branch.
   */
  private static byte[] generate(String name, boolean withLargeMethod) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
    writer.visitSource("Generated.java", null);

    MethodVisitor small = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "small", "()I", null, null);
    small.visitCode();
    line(small, 1);
    small.visitInsn(ICONST_1);
    small.visitInsn(IRETURN);
    small.visitMaxs(0, 0);
    small.visitEnd();

    if (withLargeMethod) {
      MethodVisitor run = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
      run.visitCode();
      line(run, 10);
      run.visitInsn(ICONST_0);
      run.visitVarInsn(ISTORE, 1);
      for (int k = 0; k < BRANCHES; k++) {
        line(run, 11 + k);
        Label skip = new Label();
        run.visitVarInsn(ILOAD, 0);
        run.visitJumpInsn(IFEQ, skip);
        run.visitIincInsn(1, 1);
        run.visitLabel(skip);
      }
      line(run, 11 + BRANCHES);
      run.visitVarInsn(ILOAD, 1);
      run.visitInsn(IRETURN);
      run.visitMaxs(0, 0);
      run.visitEnd();
    }
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static void line(MethodVisitor code, int line) {
    Label label = new Label();
    code.visitLabel(label);
    code.visitLineNumber(line, label);
  }

  private static final class GeneratedClassLoader extends ClassLoader {
    GeneratedClassLoader() {
      super(ProbeLossesForkedTest.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
