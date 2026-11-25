package datadog.trace.civisibility.coverage.probe;

import java.util.List;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;

/** Operand stack sizes, in slots, of method code that carries expanded stack map frames. */
final class StackSizes {

  private StackSizes() {}

  /**
   * Computes the stack size before each instruction with one pass in code order. Every instruction
   * that can be reached other than by falling through is preceded by a frame, so the pass is exact.
   *
   * @return the stack size before each node of {@code insns}, or -1 where it is unknown
   */
  static int[] compute(AbstractInsnNode[] insns) {
    int[] sizes = new int[insns.length];
    int size = 0;
    for (int i = 0; i < insns.length; i++) {
      AbstractInsnNode insn = insns[i];
      if (insn instanceof FrameNode) {
        size = frameStackSize((FrameNode) insn);
      }
      sizes[i] = size;
      int opcode = insn.getOpcode();
      if (opcode < 0 || size < 0) {
        continue;
      }
      if (opcode == Opcodes.GOTO
          || opcode == Opcodes.TABLESWITCH
          || opcode == Opcodes.LOOKUPSWITCH
          || opcode == Opcodes.ATHROW
          || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) {
        size = -1;
      } else {
        size += delta(insn);
      }
    }
    return sizes;
  }

  private static int frameStackSize(FrameNode frame) {
    List<Object> stack = frame.stack;
    if (stack == null) {
      return 0;
    }
    int size = 0;
    for (Object type : stack) {
      size += Opcodes.LONG.equals(type) || Opcodes.DOUBLE.equals(type) ? 2 : 1;
    }
    return size;
  }

  private static int delta(AbstractInsnNode insn) {
    int opcode = insn.getOpcode();
    switch (opcode) {
      case Opcodes.LDC:
        Object constant = ((LdcInsnNode) insn).cst;
        if (constant instanceof Long || constant instanceof Double) {
          return 2;
        }
        return constant instanceof ConstantDynamic ? ((ConstantDynamic) constant).getSize() : 1;
      case Opcodes.GETSTATIC:
        return Type.getType(((FieldInsnNode) insn).desc).getSize();
      case Opcodes.PUTSTATIC:
        return -Type.getType(((FieldInsnNode) insn).desc).getSize();
      case Opcodes.GETFIELD:
        return Type.getType(((FieldInsnNode) insn).desc).getSize() - 1;
      case Opcodes.PUTFIELD:
        return -Type.getType(((FieldInsnNode) insn).desc).getSize() - 1;
      case Opcodes.INVOKEVIRTUAL:
      case Opcodes.INVOKESPECIAL:
      case Opcodes.INVOKEINTERFACE:
        return invokeDelta(((MethodInsnNode) insn).desc, false);
      case Opcodes.INVOKESTATIC:
        return invokeDelta(((MethodInsnNode) insn).desc, true);
      case Opcodes.INVOKEDYNAMIC:
        return invokeDelta(((InvokeDynamicInsnNode) insn).desc, true);
      case Opcodes.MULTIANEWARRAY:
        return 1 - ((MultiANewArrayInsnNode) insn).dims;
      default:
        return InstructionSet.stackDelta(opcode);
    }
  }

  private static int invokeDelta(String descriptor, boolean noReceiver) {
    // the argument size includes one slot for the receiver
    int sizes = Type.getArgumentsAndReturnSizes(descriptor);
    return (sizes & 3) - (sizes >> 2) + (noReceiver ? 1 : 0);
  }
}
