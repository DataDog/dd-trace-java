package datadog.trace.agent.tooling.bytebuddy.iast;

import datadog.trace.api.iast.Taintable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

public class TaintableVisitor implements AsmVisitorWrapper {

  public static volatile boolean DEBUG = false;
  static volatile boolean ENABLED = true;

  private static final String INTERFACE_NAME = "datadog/trace/api/iast/Taintable";
  private static final String SOURCE_CLASS_NAME = "L" + INTERFACE_NAME + "$Source;";
  private static final String FIELD_NAME = "$$DD$source";
  private static final String GETTER_NAME = "$$DD$getSource";
  private static final String SETTER_NAME = "$$DD$setSource";

  private final Set<String> types;

  public TaintableVisitor(final String... classNames) {
    types = new HashSet<>(Arrays.asList(classNames));
  }

  @Override
  public int mergeWriter(final int flags) {
    return flags;
  }

  @Override
  public int mergeReader(int flags) {
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
    if (ENABLED) {
      return types.contains(instrumentedType.getName())
          ? new AddTaintableInterfaceVisitor(classVisitor)
          : classVisitor;
    } else {
      return NoOp.INSTANCE.wrap(
          instrumentedType,
          classVisitor,
          implementationContext,
          typePool,
          fields,
          methods,
          writerFlags,
          readerFlags);
    }
  }

  private static class AddTaintableInterfaceVisitor extends ClassVisitor {

    private String owner;

    private boolean addTaintable = true;

    protected AddTaintableInterfaceVisitor(final ClassVisitor classVisitor) {
      super(OpenedClassReader.ASM_API, classVisitor);
    }

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      owner = name;
      if (interfaces != null) {
        for (final String iface : interfaces) {
          if (INTERFACE_NAME.equals(iface)) {
            addTaintable = false;
            break;
          }
        }
      }
      super.visit(
          version,
          access,
          name,
          signature,
          superName,
          addTaintable ? addInterface(interfaces) : interfaces);
    }

    @Override
    public void visitEnd() {
      if (addTaintable) {
        addField();
        addGetter();
        if (!DEBUG) {
          addSetter();
        } else {
          addSetterDebug();
        }
      }
    }

    private String[] addInterface(@Nullable final String[] interfaces) {
      if (interfaces == null || interfaces.length == 0) {
        return new String[] {INTERFACE_NAME};
      } else {
        final String[] newInterfaces = new String[interfaces.length + 1];
        System.arraycopy(interfaces, 0, newInterfaces, 0, interfaces.length);
        newInterfaces[newInterfaces.length - 1] = INTERFACE_NAME;
        return newInterfaces;
      }
    }

    private void addField() {
      final FieldVisitor fv =
          cv.visitField(
              Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE,
              FIELD_NAME,
              SOURCE_CLASS_NAME,
              null,
              null);
      fv.visitEnd();
    }

    private void addGetter() {
      final MethodVisitor mv =
          cv.visitMethod(Opcodes.ACC_PUBLIC, GETTER_NAME, "()" + SOURCE_CLASS_NAME, null, null);
      mv.visitCode();
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitFieldInsn(Opcodes.GETFIELD, owner, FIELD_NAME, SOURCE_CLASS_NAME);
      mv.visitInsn(Opcodes.ARETURN);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }

    private void addSetter() {
      final MethodVisitor mv =
          cv.visitMethod(
              Opcodes.ACC_PUBLIC, SETTER_NAME, "(" + SOURCE_CLASS_NAME + ")V", null, null);
      mv.visitCode();
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitFieldInsn(Opcodes.PUTFIELD, owner, FIELD_NAME, SOURCE_CLASS_NAME);
      mv.visitInsn(Opcodes.RETURN);
      mv.visitMaxs(2, 2);
      mv.visitEnd();
    }

    private void addSetterDebug() {
      final MethodVisitor mv =
          cv.visitMethod(
              Opcodes.ACC_PUBLIC, SETTER_NAME, "(" + SOURCE_CLASS_NAME + ")V", null, null);
      mv.visitCode();
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitFieldInsn(Opcodes.PUTFIELD, owner, FIELD_NAME, SOURCE_CLASS_NAME);

      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          Type.getInternalName(Taintable.DebugLogger.class),
          "logTaint",
          "(Ldatadog/trace/api/iast/Taintable;)V",
          false);
      mv.visitInsn(Opcodes.RETURN);
      mv.visitMaxs(2, 2);
      mv.visitEnd();
    }
  }
}
