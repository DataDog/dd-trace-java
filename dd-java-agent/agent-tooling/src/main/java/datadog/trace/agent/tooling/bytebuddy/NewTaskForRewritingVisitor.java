package datadog.trace.agent.tooling.bytebuddy;

import static datadog.config.util.Strings.getInternalName;

import datadog.trace.bootstrap.instrumentation.java.concurrent.NewTaskForPlaceholder;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

/**
 * ASM visitor which rewrites placeholder 'newTaskFor' calls at build time to use the real method.
 */
public final class NewTaskForRewritingVisitor implements AsmVisitorWrapper {
  static final NewTaskForRewritingVisitor INSTANCE = new NewTaskForRewritingVisitor();

  static final String NEW_TASK_FOR_PLACEHOLDER_CLASS =
      getInternalName(NewTaskForPlaceholder.class.getName());

  static final String ABSTRACT_EXECUTOR_SERVICE_CLASS =
      "java/util/concurrent/AbstractExecutorService";

  static final String NEW_TASK_FOR_METHOD = "newTaskFor";

  static final String NEW_TASK_FOR_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(
          Type.getType(RunnableFuture.class),
          Type.getType(Runnable.class),
          Type.getType(Object.class));

  @Override
  public int mergeWriter(final int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(final int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      final TypeDescription instrumentedType,
      final ClassVisitor classVisitor,
      final Implementation.Context implementationContext,
      final TypePool typePool,
      final FieldList<FieldDescription.InDefinedShape> fields,
      final MethodList<?> methods,
      final int writerFlags,
      final int readerFlags) {
    return new ClassVisitor(Opcodes.ASM7, classVisitor) {
      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM7, mv) {
          @Override
          public void visitMethodInsn(
              final int opcode,
              final String owner,
              final String name,
              final String descriptor,
              final boolean isInterface) {
            // NewTaskForPlaceholder.newTaskFor(e,task,value) -> e.newTaskFor(task,value)
            if (Opcodes.INVOKESTATIC == opcode
                && NEW_TASK_FOR_PLACEHOLDER_CLASS.equals(owner)
                && NEW_TASK_FOR_METHOD.equals(name)) {
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  ABSTRACT_EXECUTOR_SERVICE_CLASS,
                  NEW_TASK_FOR_METHOD,
                  NEW_TASK_FOR_METHOD_DESCRIPTOR,
                  false);
            } else {
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
          }
        };
      }
    };
  }
}
