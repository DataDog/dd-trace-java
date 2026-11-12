package datadog.trace.instrumentation.scala213.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.bootstrap.instrumentation.scala.ScalaPromiseContinuationHelper;
import java.io.IOException;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class DefaultPromiseCallbackInstrumentationTest {
  private static final String DEFAULT_PROMISE = "scala/concurrent/impl/Promise$DefaultPromise";
  private static final String CAS_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rewritesSupportedScalaBytecode(boolean stripDebug) throws IOException {
    byte[] original = scalaBytecode();
    assertEquals(2, countCalls(original, DEFAULT_PROMISE, "compareAndSet"));

    byte[] transformed = transform(original, stripDebug ? ClassReader.SKIP_DEBUG : 0);

    assertEquals(0, countCalls(transformed, DEFAULT_PROMISE, "compareAndSet"));
    assertEquals(
        2,
        countCalls(
            transformed,
            Type.getInternalName(ScalaPromiseContinuationHelper.class),
            "compareAndSetAndCancel"));
  }

  @TableTest({
    "Scenario                 | Mutation          | Cas Count | Message                                                   ",
    "additional CAS           | EXTRA_CAS         | 3         | Expected 2 unregisterCallback compareAndSet sites, found 3",
    "one CAS removed          | MISSING_CAS       | 1         | Expected 2 unregisterCallback compareAndSet sites, found 1",
    "all CAS removed          | NO_CAS            | 0         | Expected 2 unregisterCallback compareAndSet sites, found 0",
    "method removed           | MISSING_METHOD    | 0         | Missing expected unregisterCallback method                ",
    "method made static       | STATIC_METHOD     | 0         | Expected an instance unregisterCallback method            ",
    "callback reassigned      | REASSIGN_CALLBACK | 2         | unregisterCallback overwrites its callback argument       ",
    "loaded callback replaced | REPLACE_LOADED    | 2         | unregisterCallback overwrites its callback argument       ",
    "callback slot reused     | REUSE_SLOT        | 2         | unregisterCallback overwrites its callback argument       ",
    "long overlaps callback   | LONG_OVERLAP      | 2         | unregisterCallback overwrites its callback argument       ",
    "double overlaps callback | DOUBLE_OVERLAP    | 2         | unregisterCallback overwrites its callback argument       ",
    "branch enters store      | BRANCH_TO_STORE   | 2         | unregisterCallback overwrites its callback argument       ",
    "handler enters store     | HANDLER_AT_STORE  | 2         | unregisterCallback overwrites its callback argument       "
  })
  void rejectsChangedAssumptions(Mutation mutation, int casCount, String message)
      throws IOException {
    byte[] changed = mutate(scalaBytecode(), mutation);
    assertEquals(casCount, countCalls(changed, DEFAULT_PROMISE, "compareAndSet"));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> transform(changed, 0));

    assertEquals(message, failure.getMessage());
  }

  private static byte[] scalaBytecode() throws IOException {
    try (InputStream input =
        DefaultPromiseCallbackInstrumentationTest.class
            .getClassLoader()
            .getResourceAsStream(DEFAULT_PROMISE + ".class")) {
      assertNotNull(input);
      ClassWriter writer = new ClassWriter(0);
      new ClassReader(input).accept(writer, 0);
      return writer.toByteArray();
    }
  }

  private static byte[] transform(byte[] input, int readerFlags) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    new ClassReader(input)
        .accept(
            new DefaultPromiseCallbackInstrumentation.UnregisterCallbackClassVisitor(writer, 17),
            readerFlags);
    return writer.toByteArray();
  }

  private static int countCalls(byte[] bytes, String expectedOwner, String expectedName) {
    int[] calls = {0};
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                if (!"unregisterCallback".equals(name)) {
                  return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String name,
                      String descriptor,
                      boolean isInterface) {
                    if (expectedOwner.equals(owner) && expectedName.equals(name)) {
                      calls[0]++;
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return calls[0];
  }

  private static byte[] mutate(byte[] original, Mutation mutation) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    new ClassReader(original)
        .accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                if ("unregisterCallback".equals(name)) {
                  if (mutation == Mutation.MISSING_METHOD) {
                    return null;
                  }
                  if (mutation == Mutation.STATIC_METHOD) {
                    MethodVisitor method =
                        super.visitMethod(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                            name,
                            descriptor,
                            signature,
                            exceptions);
                    method.visitCode();
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(0, 1);
                    method.visitEnd();
                    return null;
                  }
                }
                MethodVisitor method =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"unregisterCallback".equals(name)) {
                  return method;
                }
                return new MethodVisitor(Opcodes.ASM9, method) {
                  private boolean removedCas;

                  @Override
                  public void visitCode() {
                    super.visitCode();
                    mutation.insert(method);
                  }

                  @Override
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String name,
                      String descriptor,
                      boolean isInterface) {
                    if (DEFAULT_PROMISE.equals(owner)
                        && "compareAndSet".equals(name)
                        && (mutation == Mutation.NO_CAS
                            || (mutation == Mutation.MISSING_CAS && !removedCas))) {
                      removedCas = true;
                      super.visitInsn(Opcodes.POP2);
                      super.visitInsn(Opcodes.POP);
                      super.visitInsn(Opcodes.ICONST_1);
                    } else {
                      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_FRAMES);
    return writer.toByteArray();
  }

  enum Mutation {
    EXTRA_CAS,
    MISSING_CAS,
    NO_CAS,
    MISSING_METHOD,
    STATIC_METHOD,
    REASSIGN_CALLBACK,
    REPLACE_LOADED,
    REUSE_SLOT,
    LONG_OVERLAP,
    DOUBLE_OVERLAP,
    BRANCH_TO_STORE,
    HANDLER_AT_STORE;

    void insert(MethodVisitor method) {
      switch (this) {
        case EXTRA_CAS:
          method.visitVarInsn(Opcodes.ALOAD, 0);
          method.visitInsn(Opcodes.ACONST_NULL);
          method.visitInsn(Opcodes.ACONST_NULL);
          method.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, DEFAULT_PROMISE, "compareAndSet", CAS_DESCRIPTOR, false);
          method.visitInsn(Opcodes.POP);
          break;
        case REASSIGN_CALLBACK:
          method.visitInsn(Opcodes.ACONST_NULL);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          break;
        case REPLACE_LOADED:
          method.visitVarInsn(Opcodes.ALOAD, 1);
          method.visitInsn(Opcodes.POP);
          method.visitInsn(Opcodes.ACONST_NULL);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          break;
        case REUSE_SLOT:
          method.visitVarInsn(Opcodes.ALOAD, 1);
          method.visitVarInsn(Opcodes.ASTORE, 3);
          method.visitInsn(Opcodes.ICONST_0);
          method.visitVarInsn(Opcodes.ISTORE, 1);
          method.visitVarInsn(Opcodes.ALOAD, 3);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          break;
        case LONG_OVERLAP:
        case DOUBLE_OVERLAP:
          method.visitVarInsn(Opcodes.ALOAD, 0);
          method.visitVarInsn(Opcodes.ASTORE, 3);
          method.visitVarInsn(Opcodes.ALOAD, 1);
          method.visitVarInsn(Opcodes.ASTORE, 4);
          method.visitInsn(this == LONG_OVERLAP ? Opcodes.LCONST_0 : Opcodes.DCONST_0);
          method.visitVarInsn(this == LONG_OVERLAP ? Opcodes.LSTORE : Opcodes.DSTORE, 0);
          method.visitVarInsn(Opcodes.ALOAD, 3);
          method.visitVarInsn(Opcodes.ASTORE, 0);
          method.visitVarInsn(Opcodes.ALOAD, 4);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          break;
        case BRANCH_TO_STORE:
          Label load = new Label();
          Label store = new Label();
          method.visitVarInsn(Opcodes.ALOAD, 0);
          method.visitJumpInsn(Opcodes.IFNONNULL, load);
          method.visitInsn(Opcodes.ACONST_NULL);
          method.visitJumpInsn(Opcodes.GOTO, store);
          method.visitLabel(load);
          method.visitVarInsn(Opcodes.ALOAD, 1);
          method.visitLabel(store);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          break;
        case HANDLER_AT_STORE:
          Label start = new Label();
          Label end = new Label();
          Label handler = new Label();
          method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
          method.visitVarInsn(Opcodes.ALOAD, 1);
          method.visitVarInsn(Opcodes.ASTORE, 3);
          method.visitLabel(start);
          method.visitVarInsn(Opcodes.ALOAD, 0);
          method.visitInsn(Opcodes.POP);
          method.visitLabel(end);
          method.visitVarInsn(Opcodes.ALOAD, 1);
          method.visitLabel(handler);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          method.visitVarInsn(Opcodes.ALOAD, 3);
          method.visitVarInsn(Opcodes.ASTORE, 1);
          break;
        default:
          break;
      }
    }
  }
}
