package datadog.trace.instrumentation.jetty;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** If it sees an F_SAME frame together with another one, it suppresses the F_SAME one. */
public class MergeConsecutiveFramesMethodVisitor extends MethodVisitor {
  private final List<FrameVisitation> heldFrames = new ArrayList<>();

  static class FrameVisitation {
    final int type;
    final int numLocal;
    final Object[] local;
    final int numStack;
    final Object[] stack;

    FrameVisitation(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
      this.type = type;
      this.numLocal = numLocal;
      this.local = local == null ? null : local.clone();
      this.numStack = numStack;
      this.stack = stack == null ? null : stack.clone();
    }

    boolean isFSame() {
      return type == Opcodes.F_SAME;
    }

    void commit(MethodVisitor mv) {
      mv.visitFrame(type, numLocal, local, numStack, stack);
    }
  }

  public MergeConsecutiveFramesMethodVisitor(int api, MethodVisitor methodVisitor) {
    super(api, methodVisitor);
  }

  private void commitFrames() {
    if (heldFrames.isEmpty()) {
      return;
    }

    if (heldFrames.size() > 1) {
      FrameVisitation firstFrame = heldFrames.get(0);
      heldFrames.removeIf(FrameVisitation::isFSame);
      if (heldFrames.isEmpty()) {
        heldFrames.add(firstFrame); // add back one if all we have is F_SAME
      }
    }

    heldFrames.forEach(f -> f.commit(this.mv));
    heldFrames.clear();
  }

  @Override
  public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
    heldFrames.add(new FrameVisitation(type, numLocal, local, numStack, stack));
  }

  @Override
  public void visitInsn(int opcode) {
    commitFrames();
    super.visitInsn(opcode);
  }

  @Override
  public void visitIntInsn(int opcode, int operand) {
    commitFrames();
    super.visitIntInsn(opcode, operand);
  }

  @Override
  public void visitVarInsn(int opcode, int varIndex) {
    commitFrames();
    super.visitVarInsn(opcode, varIndex);
  }

  @Override
  public void visitTypeInsn(int opcode, String type) {
    commitFrames();
    super.visitTypeInsn(opcode, type);
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
    commitFrames();
    super.visitFieldInsn(opcode, owner, name, descriptor);
  }

  @Override
  public void visitMethodInsn(
      int opcode, String owner, String name, String descriptor, boolean isInterface) {
    commitFrames();
    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  @Override
  public void visitInvokeDynamicInsn(
      String name,
      String descriptor,
      Handle bootstrapMethodHandle,
      Object... bootstrapMethodArguments) {
    commitFrames();
    super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
  }

  @Override
  public void visitJumpInsn(int opcode, Label label) {
    commitFrames();
    super.visitJumpInsn(opcode, label);
  }

  @Override
  public void visitLdcInsn(Object value) {
    commitFrames();
    super.visitLdcInsn(value);
  }

  @Override
  public void visitIincInsn(int varIndex, int increment) {
    commitFrames();
    super.visitIincInsn(varIndex, increment);
  }

  @Override
  public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
    commitFrames();
    super.visitTableSwitchInsn(min, max, dflt, labels);
  }

  @Override
  public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
    commitFrames();
    super.visitLookupSwitchInsn(dflt, keys, labels);
  }

  @Override
  public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
    commitFrames();
    super.visitMultiANewArrayInsn(descriptor, numDimensions);
  }

  @Override
  public void visitEnd() {
    commitFrames();
    super.visitEnd();
  }
}
