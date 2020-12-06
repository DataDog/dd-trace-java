package datadog.trace.agent.tooling.context;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.FieldBackedContextStoreAppliedMarker;
import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/** @deprecated not used in the new field-injection strategy */
@Deprecated
@Slf4j
final class FieldInjectionVisitor implements AsmVisitorWrapper {

  private static final String INJECTED_FIELDS_MARKER_CLASS_NAME =
      Utils.getInternalName(FieldBackedContextStoreAppliedMarker.class.getName());

  private final boolean serialVersionUIDFieldInjection =
      Config.get().isSerialVersionUIDFieldInjection();

  private final TypeDescription fieldAccessorInterface;
  private final String fieldName;
  private final String getterMethodName;
  private final String setterMethodName;

  public FieldInjectionVisitor(
      TypeDescription fieldAccessorInterface,
      String fieldName,
      String getterMethodName,
      String setterMethodName) {
    this.fieldAccessorInterface = fieldAccessorInterface;
    this.fieldName = fieldName;
    this.getterMethodName = getterMethodName;
    this.setterMethodName = setterMethodName;
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
      // We are using Object class name instead of contextClassName here because this gets
      // injected onto Bootstrap classloader where context class may be unavailable
      private final TypeDescription contextType = new TypeDescription.ForLoadedType(Object.class);
      private final TypeDescription interfaceType = fieldAccessorInterface;
      private boolean foundField = false;
      private boolean foundGetter = false;
      private boolean foundSetter = false;
      private SerialVersionUIDInjector serialVersionUIDInjector;

      @Override
      public void visit(
          final int version,
          final int access,
          final String name,
          String signature,
          final String superName,
          String[] interfaces) {
        if (interfaces == null) {
          interfaces = new String[] {};
        }
        if (serialVersionUIDFieldInjection && instrumentedType.isAssignableTo(Serializable.class)) {
          serialVersionUIDInjector = new SerialVersionUIDInjector();
          serialVersionUIDInjector.visit(version, access, name, signature, superName, interfaces);
        }
        // extend interfaces and optional generic signature with marker and accessor
        final Set<String> set = new LinkedHashSet<>(Arrays.asList(interfaces));
        if (set.add(INJECTED_FIELDS_MARKER_CLASS_NAME) && null != signature) {
          signature += 'L' + INJECTED_FIELDS_MARKER_CLASS_NAME + ';';
        }
        if (set.add(interfaceType.getInternalName()) && null != signature) {
          signature += 'L' + interfaceType.getInternalName() + ';';
        }
        super.visit(version, access, name, signature, superName, set.toArray(new String[] {}));
      }

      @Override
      public FieldVisitor visitField(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final Object value) {
        if (name.equals(fieldName)) {
          foundField = true;
        } else if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.visitField(access, name, descriptor, signature, value);
        }
        return super.visitField(access, name, descriptor, signature, value);
      }

      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        if (name.equals(getterMethodName)) {
          foundGetter = true;
        } else if (name.equals(setterMethodName)) {
          foundSetter = true;
        } else if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.visitMethod(access, name, descriptor, signature, exceptions);
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
      }

      @Override
      public void visitInnerClass(String name, String outerName, String innerName, int access) {
        if (serialVersionUIDInjector != null) {
          serialVersionUIDInjector.visitInnerClass(name, outerName, innerName, access);
        }
        super.visitInnerClass(name, outerName, innerName, access);
      }

      @Override
      public void visitEnd() {
        // Checking only for field existence is not enough as libraries like CGLIB only copy
        // public/protected methods and not fields (neither public nor private ones) when
        // they enhance a class.
        // For this reason we check separately for the field and for the two accessors.
        boolean changedShape = false;
        if (!foundField) {
          cv.visitField(
              // Field should be transient to avoid being serialized with the object.
              Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT,
              fieldName,
              contextType.getDescriptor(),
              null,
              null);
          changedShape = true;
        }
        if (!foundGetter) {
          addGetter();
          changedShape = true;
        }
        if (!foundSetter) {
          addSetter();
          changedShape = true;
        }
        if (changedShape && serialVersionUIDInjector != null) {
          serialVersionUIDInjector.injectSerialVersionUID(instrumentedType, cv);
          serialVersionUIDInjector = null;
        }
        super.visitEnd();
      }

      /** Just 'standard' getter implementation */
      private void addGetter() {
        final MethodVisitor mv = getAccessorMethodVisitor(getterMethodName);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(
            Opcodes.GETFIELD,
            instrumentedType.getInternalName(),
            fieldName,
            contextType.getDescriptor());
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      /** Just 'standard' setter implementation */
      private void addSetter() {
        final MethodVisitor mv = getAccessorMethodVisitor(setterMethodName);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(
            Opcodes.PUTFIELD,
            instrumentedType.getInternalName(),
            fieldName,
            contextType.getDescriptor());
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
      }

      private MethodVisitor getAccessorMethodVisitor(final String methodName) {
        return cv.visitMethod(
            Opcodes.ACC_PUBLIC,
            methodName,
            Utils.getMethodDefinition(interfaceType, methodName).getDescriptor(),
            null,
            null);
      }
    };
  }

  private static final class SerialVersionUIDInjector
      extends datadog.trace.agent.tooling.context.asm.SerialVersionUIDAdder {
    public SerialVersionUIDInjector() {
      super(Opcodes.ASM7, null);
    }

    public void injectSerialVersionUID(
        final TypeDescription instrumentedType, final ClassVisitor transformer) {
      if (!hasSVUID()) {
        try {
          transformer.visitField(
              Opcodes.ACC_FINAL | Opcodes.ACC_STATIC,
              "serialVersionUID",
              "J",
              null,
              computeSVUID());
        } catch (Exception e) {
          log.debug("Failed to add serialVersionUID to {}", instrumentedType.getActualName(), e);
        }
      }
    }
  }
}
