package datadog.trace.instrumentation.jetty;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.TypePath;

public class DelayLoadsMethodVisitor extends MethodVisitor {
  private final List<Integer> heldLoads = new ArrayList();

  public DelayLoadsMethodVisitor(int api, MethodVisitor methodVisitor) {
    super(api, methodVisitor);
  }

  public void commitLoads(List<Integer> heldLoads) {
    for (Integer idx : heldLoads) {
      super.visitVarInsn(Opcodes.ALOAD, idx);
    }
    heldLoads.clear();
  }

  public List<Integer> transferLoads() {
    ArrayList<Integer> copy = new ArrayList<>(this.heldLoads);
    this.heldLoads.clear();
    return copy;
  }

  private void commitLoads() {
    commitLoads(this.heldLoads);
  }

  @Override
  public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
    commitLoads();
    super.visitFrame(type, numLocal, local, numStack, stack);
  }

  @Override
  public void visitInsn(int opcode) {
    commitLoads();
    super.visitInsn(opcode);
  }

  @Override
  public void visitIntInsn(int opcode, int operand) {
    commitLoads();
    super.visitIntInsn(opcode, operand);
  }

  @Override
  public void visitVarInsn(int opcode, int varIndex) {
    if (opcode == Opcodes.ALOAD) {
      heldLoads.add(varIndex);
    } else {
      commitLoads();
      super.visitVarInsn(opcode, varIndex);
    }
  }

  @Override
  public void visitTypeInsn(int opcode, String type) {
    commitLoads();
    super.visitTypeInsn(opcode, type);
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
    commitLoads();
    super.visitFieldInsn(opcode, owner, name, descriptor);
  }

  @Override
  public void visitMethodInsn(int opcode, String owner, String name, String descriptor) {
    commitLoads();
    super.visitMethodInsn(opcode, owner, name, descriptor);
  }

  @Override
  public void visitMethodInsn(
      int opcode, String owner, String name, String descriptor, boolean isInterface) {
    commitLoads();
    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  @Override
  public void visitInvokeDynamicInsn(
      String name,
      String descriptor,
      Handle bootstrapMethodHandle,
      Object... bootstrapMethodArguments) {
    commitLoads();
    super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
  }

  @Override
  public void visitJumpInsn(int opcode, Label label) {
    commitLoads();
    super.visitJumpInsn(opcode, label);
  }

  @Override
  public void visitLabel(Label label) {
    commitLoads();
    super.visitLabel(label);
  }

  @Override
  public void visitLdcInsn(Object value) {
    commitLoads();
    super.visitLdcInsn(value);
  }

  @Override
  public void visitIincInsn(int varIndex, int increment) {
    commitLoads();
    super.visitIincInsn(varIndex, increment);
  }

  @Override
  public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
    commitLoads();
    super.visitTableSwitchInsn(min, max, dflt, labels);
  }

  @Override
  public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
    commitLoads();
    super.visitLookupSwitchInsn(dflt, keys, labels);
  }

  @Override
  public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
    commitLoads();
    super.visitMultiANewArrayInsn(descriptor, numDimensions);
  }

  @Override
  public AnnotationVisitor visitInsnAnnotation(
      int typeRef, TypePath typePath, String descriptor, boolean visible) {
    commitLoads();
    return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
  }

  @Override
  public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
    commitLoads();
    super.visitTryCatchBlock(start, end, handler, type);
  }

  @Override
  public AnnotationVisitor visitTryCatchAnnotation(
      int typeRef, TypePath typePath, String descriptor, boolean visible) {
    commitLoads();
    return super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible);
  }

  @Override
  public void visitLocalVariable(
      String name, String descriptor, String signature, Label start, Label end, int index) {
    commitLoads();
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
    commitLoads();
    return super.visitLocalVariableAnnotation(
        typeRef, typePath, start, end, index, descriptor, visible);
  }

  @Override
  public void visitLineNumber(int line, Label start) {
    commitLoads();
    super.visitLineNumber(line, start);
  }

  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    commitLoads();
    super.visitMaxs(maxStack, maxLocals);
  }

  @Override
  public void visitEnd() {
    commitLoads();
    super.visitEnd();
  }
}
