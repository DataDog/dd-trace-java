package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IF_ICMPGE;
import static org.objectweb.asm.Opcodes.IF_ICMPNE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IMUL;
import static org.objectweb.asm.Opcodes.IREM;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.SIPUSH;
import static org.objectweb.asm.Opcodes.V1_8;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SharedPart;
import java.util.BitSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * A method whose instrumented code outgrows 2-byte jump offsets, shaped like a generated lexer: a
 * loop around a large switch, followed by long straight-line code. It must run unchanged and report
 * its lines. The sizes vary so that the loop's backward jumps fall on both sides of the 2-byte
 * offset range once the probes are in.
 */
class LargeMethodTest {

  private static final String NAME = "datadog/trace/civisibility/coverage/probe/GeneratedLexer";

  /** Lines of {@code sum = sum * 31 + 7} before the return. */
  private static final int TAIL_LINES = 2500;

  /** Added to a case's index to get a value that the loop index never takes. */
  private static final int NEVER = 1_000_000;

  private static final int RUNS = 3;

  @ParameterizedTest
  @ValueSource(ints = {600, 620, 640, 650, 660, 670, 680, 690, 700, 710, 720})
  void runsAndReportsItsLines(int cases) throws Exception {
    GeneratedClassLoader loader = new GeneratedClassLoader();
    byte[] instrumented = ProbeClassTransformer.instrument(generate(cases), loader, true);
    assertNotNull(instrumented, "not instrumented");

    Class<?> lexer = loader.define(NAME.replace('/', '.'), instrumented);
    assertEquals(expectedResult(), lexer.getMethod("run", int.class).invoke(null, RUNS));
    assertEquals(expectedLines(cases), coveredLines(loader));
  }

  /**
   * <pre>
   * static int run(int n) {
   *   int sum = 0;                         // line 1
   *   int i = 0;                           // line 2
   *   while (i < n) {                      // line 3
   *     switch (i % cases) {               // line 4
   *       case k:
   *         if (i == k + NEVER)            // line 5 + 3k
   *           return k;                    // line 6 + 3k
   *         sum += k;                      // line 7 + 3k
   *         break;
   *     }
   *     i++;                               // line 5 + 3 * cases
   *   }
   *   sum = sum * 31 + 7;                  // TAIL_LINES lines from 6 + 3 * cases
   *   return sum;                          // the line after them
   * }
   * </pre>
   */
  private static byte[] generate(int cases) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NAME, null, "java/lang/Object", null);
    writer.visitSource("GeneratedLexer.java", null);
    MethodVisitor code = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
    code.visitCode();
    line(code, 1);
    code.visitInsn(ICONST_0);
    code.visitVarInsn(ISTORE, 1);
    line(code, 2);
    code.visitInsn(ICONST_0);
    code.visitVarInsn(ISTORE, 2);

    Label loop = line(code, 3);
    Label exit = new Label();
    code.visitVarInsn(ILOAD, 2);
    code.visitVarInsn(ILOAD, 0);
    code.visitJumpInsn(IF_ICMPGE, exit);
    line(code, 4);
    code.visitVarInsn(ILOAD, 2);
    push(code, cases);
    code.visitInsn(IREM);
    Label next = new Label();
    Label[] labels = new Label[cases];
    for (int k = 0; k < cases; k++) {
      labels[k] = new Label();
    }
    code.visitTableSwitchInsn(0, cases - 1, next, labels);
    for (int k = 0; k < cases; k++) {
      code.visitLabel(labels[k]);
      line(code, 5 + 3 * k);
      Label add = new Label();
      code.visitVarInsn(ILOAD, 2);
      code.visitLdcInsn(k + NEVER);
      code.visitJumpInsn(IF_ICMPNE, add);
      line(code, 6 + 3 * k);
      push(code, k);
      code.visitInsn(IRETURN);
      code.visitLabel(add);
      line(code, 7 + 3 * k);
      code.visitVarInsn(ILOAD, 1);
      push(code, k);
      code.visitInsn(IADD);
      code.visitVarInsn(ISTORE, 1);
      code.visitJumpInsn(GOTO, next);
    }
    code.visitLabel(next);
    line(code, 5 + 3 * cases);
    code.visitIincInsn(2, 1);
    code.visitJumpInsn(GOTO, loop);

    code.visitLabel(exit);
    for (int k = 0; k < TAIL_LINES; k++) {
      line(code, 6 + 3 * cases + k);
      code.visitVarInsn(ILOAD, 1);
      code.visitIntInsn(BIPUSH, 31);
      code.visitInsn(IMUL);
      code.visitIntInsn(BIPUSH, 7);
      code.visitInsn(IADD);
      code.visitVarInsn(ISTORE, 1);
    }
    line(code, 6 + 3 * cases + TAIL_LINES);
    code.visitVarInsn(ILOAD, 1);
    code.visitInsn(IRETURN);
    code.visitMaxs(0, 0);
    code.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static Label line(MethodVisitor code, int line) {
    Label label = new Label();
    code.visitLabel(label);
    code.visitLineNumber(line, label);
    return label;
  }

  private static void push(MethodVisitor code, int value) {
    if (value <= 5) {
      code.visitInsn(ICONST_0 + value);
    } else if (value <= Byte.MAX_VALUE) {
      code.visitIntInsn(BIPUSH, value);
    } else {
      code.visitIntInsn(SIPUSH, value);
    }
  }

  private static int expectedResult() {
    int sum = 0;
    for (int i = 0; i < RUNS; i++) {
      sum += i;
    }
    for (int k = 0; k < TAIL_LINES; k++) {
      sum = sum * 31 + 7;
    }
    return sum;
  }

  private static BitSet expectedLines(int cases) {
    BitSet lines = new BitSet();
    lines.set(1, 5);
    for (int k = 0; k < RUNS; k++) {
      lines.set(5 + 3 * k);
      lines.set(7 + 3 * k);
    }
    lines.set(5 + 3 * cases);
    lines.set(6 + 3 * cases, 6 + 3 * cases + TAIL_LINES + 1);
    return lines;
  }

  /** The lines that ran, with no test window open. */
  private static BitSet coveredLines(ClassLoader loader) {
    BitSet lines = new BitSet();
    ProbeRecorder.flushShared();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass != null && instrumentedClass.loader() == loader) {
        ProbeRecorder.addSharedLines(SharedPart.UNOWNED, classId, lines);
      }
    }
    return lines;
  }

  private static final class GeneratedClassLoader extends ClassLoader {
    GeneratedClassLoader() {
      super(LargeMethodTest.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
