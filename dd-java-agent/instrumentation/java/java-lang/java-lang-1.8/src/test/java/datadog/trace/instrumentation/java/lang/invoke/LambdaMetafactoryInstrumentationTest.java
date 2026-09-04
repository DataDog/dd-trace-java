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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformerHelper;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformerHolder;
import datadog.trace.instrumentation.java.lang.invoke.LambdaMetafactoryInstrumentation.MetafactoryVisitorWrapper;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import org.junit.jupiter.api.Test;

class LambdaMetafactoryInstrumentationTest {

  private static final String HELPER =
      "datadog/trace/bootstrap/instrumentation/java/lang/invoke/LambdaTransformerHelper";

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
            .wrap(realMetafactoryDescription(), out, null, null, null, null, 0, 0);
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
                        && "([BLjava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)[B"
                            .equals(desc)) {
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
                  INVOKEVIRTUAL,
                  "jdk/internal/org/objectweb/asm/ClassWriter",
                  "toByteArray",
                  "()[B",
                  false);
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
                  INVOKEVIRTUAL,
                  "jdk/internal/org/objectweb/asm/ClassWriter",
                  "toByteArray",
                  "()[B",
                  false);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  @Test
  void injectsAfterBuildOnClassFileApi() {
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
                  "(Ljava/lang/classfile/constantpool/ClassEntry;"
                      + "Ljava/lang/classfile/constantpool/ConstantPoolBuilder;"
                      + "Ljava/util/function/Consumer;)[B",
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
                  INVOKEVIRTUAL,
                  "jdk/internal/org/objectweb/asm/ClassWriter",
                  "toByteArray",
                  "()[B",
                  false);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  @Test
  void doesNotInjectOnUnrelatedToByteArrayOwner() {
    assertFalse(
        injectsTransformCall(
            "spinInnerClass",
            "()Ljava/lang/Class;",
            mv -> {
              mv.visitInsn(ACONST_NULL);
              mv.visitMethodInsn(
                  INVOKEVIRTUAL, "java/io/ByteArrayOutputStream", "toByteArray", "()[B", false);
              mv.visitInsn(POP);
              mv.visitInsn(ACONST_NULL);
              mv.visitInsn(ARETURN);
            }));
  }

  /** Verifies every field read by the injected bytecode, including inherited fields. */
  @Test
  void structureMatcherAcceptsTheRealMetafactory() {
    assertTrue(
        new LambdaMetafactoryInstrumentation()
            .structureMatcher()
            .matches(realMetafactoryDescription()));
  }

  @Test
  void structureMatcherAcceptsCurrentInterfaceField() {
    assertTrue(
        new LambdaMetafactoryInstrumentation()
            .structureMatcher()
            .matches(TypeDescription.ForLoadedType.of(CurrentMetafactoryFields.class)));
  }

  @Test
  void structureMatcherAcceptsLegacyInterfaceField() {
    assertTrue(
        new LambdaMetafactoryInstrumentation()
            .structureMatcher()
            .matches(TypeDescription.ForLoadedType.of(LegacyMetafactoryFields.class)));
  }

  @Test
  void structureMatcherRejectsTypeWithoutTheFields() {
    assertFalse(
        new LambdaMetafactoryInstrumentation()
            .structureMatcher()
            .matches(TypeDescription.ForLoadedType.of(Object.class)));
  }

  private static TypeDescription realMetafactoryDescription() {
    try {
      return TypeDescription.ForLoadedType.of(
          Class.forName("java.lang.invoke.InnerClassLambdaMetafactory"));
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  private static final class CurrentMetafactoryFields {
    private String lambdaClassName;
    private Class<?> targetClass;
    private Class<?> interfaceClass;
  }

  private static final class LegacyMetafactoryFields {
    private String lambdaClassName;
    private Class<?> targetClass;
    private Class<?> samBase;
  }

  @Test
  void registeredTransformerReceivesFunctionalInterface() {
    byte[] originalBytes = new byte[0];
    AtomicBoolean transformed = new AtomicBoolean();
    AtomicReference<String> transformedInterface = new AtomicReference<>();
    LambdaTransformerHolder.set(
        (className, targetClass, classBytes, interfaceClassName) -> {
          transformed.set(true);
          transformedInterface.set(interfaceClassName);
          return classBytes;
        });
    try {
      byte[] result =
          LambdaTransformerHelper.transform(
              originalBytes, "test/Lambda", Object.class, Runnable.class);

      assertSame(originalBytes, result);
      assertTrue(transformed.get());
      assertEquals(Runnable.class.getName(), transformedInterface.get());
    } finally {
      LambdaTransformerHolder.set(null);
    }
  }

  @Test
  void transformerFailureFallsBackAndDoesNotPoisonNextLambda() {
    byte[] originalBytes = new byte[0];
    byte[] transformedBytes = new byte[1];
    AtomicInteger calls = new AtomicInteger();
    LambdaTransformerHolder.set(
        (className, targetClass, classBytes, interfaceClassName) -> {
          if (calls.getAndIncrement() == 0) {
            throw new IllegalStateException("expected test failure");
          }
          return transformedBytes;
        });
    try {
      assertSame(
          originalBytes,
          LambdaTransformerHelper.transform(
              originalBytes, "test/Lambda", Object.class, Runnable.class));
      assertSame(
          transformedBytes,
          LambdaTransformerHelper.transform(
              originalBytes, "test/Lambda", Object.class, Runnable.class));
      assertEquals(2, calls.get());
    } finally {
      LambdaTransformerHolder.set(null);
    }
  }

  @Test
  void nullTransformFallsBackAndDoesNotPoisonNextLambda() {
    byte[] originalBytes = new byte[0];
    byte[] transformedBytes = new byte[1];
    AtomicInteger calls = new AtomicInteger();
    LambdaTransformerHolder.set(
        (className, targetClass, classBytes, interfaceClassName) ->
            calls.getAndIncrement() == 0 ? null : transformedBytes);
    try {
      assertSame(
          originalBytes,
          LambdaTransformerHelper.transform(
              originalBytes, "test/Lambda", Object.class, Runnable.class));
      assertSame(
          transformedBytes,
          LambdaTransformerHelper.transform(
              originalBytes, "test/Lambda", Object.class, Runnable.class));
      assertEquals(2, calls.get());
    } finally {
      LambdaTransformerHolder.set(null);
    }
  }

  @FunctionalInterface
  private interface ClassBody {
    void write(MethodVisitor mv);
  }
}
