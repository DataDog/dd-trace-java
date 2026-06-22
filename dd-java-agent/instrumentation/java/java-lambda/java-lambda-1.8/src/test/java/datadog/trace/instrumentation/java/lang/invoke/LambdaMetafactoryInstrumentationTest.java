package datadog.trace.instrumentation.java.lang.invoke;

import static net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.ACONST_NULL;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.ASM7;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEINTERFACE;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;
import static net.bytebuddy.jar.asm.Opcodes.POP;
import static net.bytebuddy.jar.asm.Opcodes.V1_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.instrumentation.java.lang.invoke.LambdaMetafactoryInstrumentation.MetafactoryVisitorWrapper;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import org.junit.jupiter.api.Test;

class LambdaMetafactoryInstrumentationTest {

  private static final String HELPER =
      "datadog/trace/bootstrap/instrumentation/java/lang/invoke/LambdaTransformerHelper";

  /**
   * Builds a class with a single method, transforms it, and reports whether transform() was hit.
   */
  private static boolean injectsTransformCall(
      String methodName, String methodDescriptor, ClassBody body) {
    ClassWriter in = new ClassWriter(0);
    in.visit(V1_8, ACC_PUBLIC, "Dummy", null, "java/lang/Object", null);
    MethodVisitor mv = in.visitMethod(ACC_PRIVATE, methodName, methodDescriptor, null, null);
    mv.visitCode();
    body.write(mv);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
    in.visitEnd();

    ClassWriter out = new ClassWriter(0);
    ClassVisitor visitor =
        new MetafactoryVisitorWrapper()
            .wrap(
                TypeDescription.ForLoadedType.of(Object.class), out, null, null, null, null, 0, 0);
    new ClassReader(in.toByteArray()).accept(visitor, 0);

    AtomicBoolean found = new AtomicBoolean(false);
    new ClassReader(out.toByteArray())
        .accept(
            new ClassVisitor(ASM7) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(ASM7) {
                  @Override
                  public void visitMethodInsn(
                      int opcode, String owner, String name, String desc, boolean itf) {
                    if (opcode == INVOKESTATIC
                        && HELPER.equals(owner)
                        && "transform".equals(name)
                        && "([BLjava/lang/String;Ljava/lang/Class;)[B".equals(desc)) {
                      found.set(true);
                    }
                  }
                };
              }
            },
            0);
    return found.get();
  }

  @Test
  void injectsAfterToByteArrayInSpinInnerClass() {
    assertTrue(
        injectsTransformCall(
            "spinInnerClass",
            "()Ljava/lang/Class;",
            mv -> {
              mv.visitInsn(ACONST_NULL);
              mv.visitMethodInsn(
                  INVOKEVIRTUAL, "net/bytebuddy/jar/asm/ClassWriter", "toByteArray", "()[B", false);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  @Test
  void injectsAfterToByteArrayInGenerateInnerClass() {
    assertTrue(
        injectsTransformCall(
            "generateInnerClass",
            "()Ljava/lang/Class;",
            mv -> {
              mv.visitInsn(ACONST_NULL);
              mv.visitMethodInsn(
                  INVOKEVIRTUAL, "net/bytebuddy/jar/asm/ClassWriter", "toByteArray", "()[B", false);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  @Test
  void injectsAfterBuildOnJdk24() {
    assertTrue(
        injectsTransformCall(
            "spinInnerClass",
            "()Ljava/lang/Class;",
            mv -> {
              mv.visitInsn(ACONST_NULL);
              mv.visitMethodInsn(
                  INVOKEINTERFACE,
                  "java/lang/classfile/ClassFile",
                  "build",
                  "(Ljava/lang/constant/ClassDesc;Ljava/util/function/Consumer;)[B",
                  true);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  @Test
  void doesNotInjectInUnrelatedMethod() {
    assertFalse(
        injectsTransformCall(
            "someOtherMethod",
            "()Ljava/lang/Class;",
            mv -> {
              mv.visitInsn(ACONST_NULL);
              mv.visitMethodInsn(
                  INVOKEVIRTUAL, "net/bytebuddy/jar/asm/ClassWriter", "toByteArray", "()[B", false);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  @FunctionalInterface
  private interface ClassBody {
    void write(MethodVisitor mv);
  }
}
