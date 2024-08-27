package datadog.trace.instrumentation.jetty9;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.TypePath;

public class DelayCertainInsMethodVisitor extends MethodVisitor {
  private final List<Function> heldVisitations = new ArrayList();

  public DelayCertainInsMethodVisitor(int api, MethodVisitor methodVisitor) {
    super(api, methodVisitor);
  }

  public void commitVisitations(List<Function> heldVisitations) {
    for (Function fun : heldVisitations) {
      fun.apply(null);
    }
    heldVisitations.clear();
  }

  public List<Function> transferVisitations() {
    ArrayList<Function> copy = new ArrayList<>(this.heldVisitations);
    this.heldVisitations.clear();
    return copy;
  }

  private void commitVisitations() {
    commitVisitations(this.heldVisitations);
  }

  @Override
  public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
    commitVisitations();
    super.visitFrame(type, numLocal, local, numStack, stack);
  }

  @Override
  public void visitInsn(int opcode) {
    commitVisitations();
    super.visitInsn(opcode);
  }

  @Override
  public void visitIntInsn(int opcode, int operand) {
    commitVisitations();
    super.visitIntInsn(opcode, operand);
  }

  @Override
  public void visitVarInsn(final int opcode, final int varIndex) {
    if (opcode == Opcodes.ALOAD) {
      heldVisitations.add(new ALoadVarInsn(opcode, varIndex));
    } else {
      commitVisitations();
      super.visitVarInsn(opcode, varIndex);
    }
  }

  @Override
  public void visitTypeInsn(int opcode, String type) {
    commitVisitations();
    super.visitTypeInsn(opcode, type);
  }

  @Override
  public void visitFieldInsn(
      final int opcode, final String owner, final String name, final String descriptor) {
    if (opcode == Opcodes.GETSTATIC) {
      heldVisitations.add(new GetStaticFieldInsn(opcode, owner, name, descriptor));
    } else if (opcode == Opcodes.GETFIELD) {
      heldVisitations.add(new GetFieldInsn(opcode, owner, name, descriptor));
    } else {
      commitVisitations();
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }
  }

  @Override
  public void visitMethodInsn(
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
    if (opcode == Opcodes.INVOKEVIRTUAL) {
      heldVisitations.add(new VirtualMethodInsn(opcode, owner, name, descriptor, isInterface));
    } else {
      commitVisitations();
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
  }

  @Override
  public void visitInvokeDynamicInsn(
      final String name,
      final String descriptor,
      final Handle bootstrapMethodHandle,
      final Object... bootstrapMethodArguments) {
    heldVisitations.add(
        new InvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments));
  }

  @Override
  public void visitJumpInsn(int opcode, Label label) {
    commitVisitations();
    super.visitJumpInsn(opcode, label);
  }

  @Override
  public void visitLabel(Label label) {
    commitVisitations();
    super.visitLabel(label);
  }

  @Override
  public void visitLdcInsn(Object value) {
    commitVisitations();
    super.visitLdcInsn(value);
  }

  @Override
  public void visitIincInsn(int varIndex, int increment) {
    commitVisitations();
    super.visitIincInsn(varIndex, increment);
  }

  @Override
  public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
    commitVisitations();
    super.visitTableSwitchInsn(min, max, dflt, labels);
  }

  @Override
  public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
    commitVisitations();
    super.visitLookupSwitchInsn(dflt, keys, labels);
  }

  @Override
  public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
    commitVisitations();
    super.visitMultiANewArrayInsn(descriptor, numDimensions);
  }

  @Override
  public AnnotationVisitor visitInsnAnnotation(
      int typeRef, TypePath typePath, String descriptor, boolean visible) {
    commitVisitations();
    return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
  }

  @Override
  public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
    commitVisitations();
    super.visitTryCatchBlock(start, end, handler, type);
  }

  @Override
  public AnnotationVisitor visitTryCatchAnnotation(
      int typeRef, TypePath typePath, String descriptor, boolean visible) {
    commitVisitations();
    return super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible);
  }

  @Override
  public void visitLocalVariable(
      String name, String descriptor, String signature, Label start, Label end, int index) {
    commitVisitations();
    super.visitLocalVariable(name, descriptor, signature, start, end, index);
  }

  @Override
  public AnnotationVisitor visitLocalVariableAnnotation(
      int typeRef,
      TypePath typePath,
      Label[] start,
      Label[] end,
      int[] index,
      String descriptor,
      boolean visible) {
    commitVisitations();
    return super.visitLocalVariableAnnotation(
        typeRef, typePath, start, end, index, descriptor, visible);
  }

  @Override
  public void visitLineNumber(int line, Label start) {
    commitVisitations();
    super.visitLineNumber(line, start);
  }

  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    commitVisitations();
    super.visitMaxs(maxStack, maxLocals);
  }

  @Override
  public void visitEnd() {
    commitVisitations();
    super.visitEnd();
  }

  public class InvokeDynamicInsn implements Function {
    public final String name;
    public final String descriptor;
    public final Handle bootstrapMethodHandle;
    public final Object[] bootstrapMethodArguments;

    public InvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments) {
      this.name = name;
      this.descriptor = descriptor;
      this.bootstrapMethodHandle = bootstrapMethodHandle;
      this.bootstrapMethodArguments = bootstrapMethodArguments;
    }

    @Override
    public Object apply(Object input) {
      mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
      return null;
    }
  }

  public class VirtualMethodInsn implements Function {
    public final int opcode;
    public final String owner;
    public final String name;
    public final String descriptor;
    public final boolean isInterface;

    public VirtualMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      this.opcode = opcode;
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
      this.isInterface = isInterface;
    }

    @Override
    public Object apply(Object input) {
      mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      return null;
    }
  }

  public class GetStaticFieldInsn implements Function {
    public final int opcode;
    public final String owner;
    public final String name;
    public final String descriptor;

    public GetStaticFieldInsn(int opcode, String owner, String name, String descriptor) {
      this.opcode = opcode;
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
    }

    @Override
    public Object apply(Object input) {
      mv.visitFieldInsn(opcode, owner, name, descriptor);
      return null;
    }
  }

  public class GetFieldInsn implements Function {
    public final int opcode;
    public final String owner;
    public final String name;
    public final String descriptor;

    public GetFieldInsn(int opcode, String owner, String name, String descriptor) {
      this.opcode = opcode;
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
    }

    @Override
    public Object apply(Object input) {
      mv.visitFieldInsn(opcode, owner, name, descriptor);
      return null;
    }
  }

  public class ALoadVarInsn implements Function {
    public final int opcode;
    public final int varIndex;

    public ALoadVarInsn(int opcode, int varIndex) {
      this.opcode = opcode;
      this.varIndex = varIndex;
    }

    @Override
    public Object apply(Object input) {
      mv.visitVarInsn(opcode, varIndex);
      return null;
    }
  }
}
