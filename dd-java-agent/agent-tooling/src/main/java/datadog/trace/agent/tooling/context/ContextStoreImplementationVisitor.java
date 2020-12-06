package datadog.trace.agent.tooling.context;

import datadog.trace.agent.tooling.Utils;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/** @deprecated not used in the new field-injection strategy */
@Deprecated
final class ContextStoreImplementationVisitor implements AsmVisitorWrapper {

  private final String setterName;
  private final String getterName;
  private final TypeDescription accessorInterface;

  public ContextStoreImplementationVisitor(
      String setterName, String getterName, TypeDescription accessorInterface) {
    this.setterName = setterName;
    this.getterName = getterName;
    this.accessorInterface = accessorInterface;
  }

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

      private final String accessorInterfaceInternalName = accessorInterface.getInternalName();
      private final String instrumentedTypeInternalName = instrumentedType.getInternalName();
      private final boolean frames =
          implementationContext.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6);

      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        if ("realGet".equals(name)) {
          generateRealGetMethod(name);
          return null;
        } else if ("realPut".equals(name)) {
          generateRealPutMethod(name);
          return null;
        } else if ("realSynchronizeInstance".equals(name)) {
          generateRealSynchronizeInstanceMethod(name);
          return null;
        } else {
          return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
      }

      /**
       * Provides implementation for {@code realGet} method that looks like this
       *
       * <blockquote>
       *
       * <pre>
       * private Object realGet(final Object key) {
       *   if (key instanceof $accessorInterfaceInternalName) {
       *     return (($accessorInterfaceInternalName) key).$getterName();
       *   } else {
       *     return mapGet(key);
       *   }
       * }
       * </pre>
       *
       * </blockquote>
       *
       * @param name name of the method being visited
       */
      private void generateRealGetMethod(final String name) {
        final Label elseLabel = new Label();
        final MethodVisitor mv = getMethodVisitor(name);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, accessorInterfaceInternalName);
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, accessorInterfaceInternalName);
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            accessorInterfaceInternalName,
            getterName,
            Utils.getMethodDefinition(accessorInterface, getterName).getDescriptor(),
            true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(elseLabel);
        if (frames) {
          mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            instrumentedTypeInternalName,
            "mapGet",
            Utils.getMethodDefinition(instrumentedType, "mapGet").getDescriptor(),
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      /**
       * Provides implementation for {@code realPut} method that looks like this
       *
       * <blockquote>
       *
       * <pre>
       * private void realPut(final Object key, final Object value) {
       *   if (key instanceof $accessorInterfaceInternalName) {
       *     (($accessorInterfaceInternalName) key).$setterName(value);
       *   } else {
       *     mapPut(key, value);
       *   }
       * }
       * </pre>
       *
       * </blockquote>
       *
       * @param name name of the method being visited
       */
      private void generateRealPutMethod(final String name) {
        final Label elseLabel = new Label();
        final Label endLabel = new Label();
        final MethodVisitor mv = getMethodVisitor(name);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, accessorInterfaceInternalName);
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, accessorInterfaceInternalName);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            accessorInterfaceInternalName,
            setterName,
            Utils.getMethodDefinition(accessorInterface, setterName).getDescriptor(),
            true);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        mv.visitLabel(elseLabel);
        if (frames) {
          mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            instrumentedTypeInternalName,
            "mapPut",
            Utils.getMethodDefinition(instrumentedType, "mapPut").getDescriptor(),
            false);
        mv.visitLabel(endLabel);
        if (frames) {
          mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      /**
       * Provides implementation for {@code realSynchronizeInstance} method that looks like this
       *
       * <blockquote>
       *
       * <pre>
       * private Object realSynchronizeInstance(final Object key) {
       *   if (key instanceof $accessorInterfaceInternalName) {
       *     return key;
       *   } else {
       *     return mapSynchronizeInstance(key);
       *   }
       * }
       * </pre>
       *
       * </blockquote>
       *
       * @param name name of the method being visited
       */
      private void generateRealSynchronizeInstanceMethod(final String name) {
        final MethodVisitor mv = getMethodVisitor(name);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, accessorInterfaceInternalName);
        final Label elseLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(elseLabel);
        if (frames) {
          mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            instrumentedTypeInternalName,
            "mapSynchronizeInstance",
            Utils.getMethodDefinition(instrumentedType, "mapSynchronizeInstance").getDescriptor(),
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      private MethodVisitor getMethodVisitor(final String methodName) {
        return cv.visitMethod(
            Opcodes.ACC_PRIVATE,
            methodName,
            Utils.getMethodDefinition(instrumentedType, methodName).getDescriptor(),
            null,
            null);
      }
    };
  }
}
