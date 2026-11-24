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

  private static final int[] DELTA = new int[256];

  static {
    set(
        1,
        Opcodes.ACONST_NULL,
        Opcodes.ICONST_M1,
        Opcodes.ICONST_0,
        Opcodes.ICONST_1,
        Opcodes.ICONST_2,
        Opcodes.ICONST_3,
        Opcodes.ICONST_4,
        Opcodes.ICONST_5,
        Opcodes.FCONST_0,
        Opcodes.FCONST_1,
        Opcodes.FCONST_2,
        Opcodes.BIPUSH,
        Opcodes.SIPUSH,
        Opcodes.ILOAD,
        Opcodes.FLOAD,
        Opcodes.ALOAD,
        Opcodes.DUP,
        Opcodes.DUP_X1,
        Opcodes.DUP_X2,
        Opcodes.I2L,
        Opcodes.I2D,
        Opcodes.F2L,
        Opcodes.F2D,
        Opcodes.NEW,
        Opcodes.JSR);
    set(
        2,
        Opcodes.LCONST_0,
        Opcodes.LCONST_1,
        Opcodes.DCONST_0,
        Opcodes.DCONST_1,
        Opcodes.LLOAD,
        Opcodes.DLOAD,
        Opcodes.DUP2,
        Opcodes.DUP2_X1,
        Opcodes.DUP2_X2);
    set(
        -1,
        Opcodes.IALOAD,
        Opcodes.FALOAD,
        Opcodes.AALOAD,
        Opcodes.BALOAD,
        Opcodes.CALOAD,
        Opcodes.SALOAD,
        Opcodes.ISTORE,
        Opcodes.FSTORE,
        Opcodes.ASTORE,
        Opcodes.POP,
        Opcodes.IADD,
        Opcodes.FADD,
        Opcodes.ISUB,
        Opcodes.FSUB,
        Opcodes.IMUL,
        Opcodes.FMUL,
        Opcodes.IDIV,
        Opcodes.FDIV,
        Opcodes.IREM,
        Opcodes.FREM,
        Opcodes.ISHL,
        Opcodes.ISHR,
        Opcodes.IUSHR,
        Opcodes.LSHL,
        Opcodes.LSHR,
        Opcodes.LUSHR,
        Opcodes.IAND,
        Opcodes.IOR,
        Opcodes.IXOR,
        Opcodes.L2I,
        Opcodes.L2F,
        Opcodes.D2I,
        Opcodes.D2F,
        Opcodes.FCMPL,
        Opcodes.FCMPG,
        Opcodes.IFEQ,
        Opcodes.IFNE,
        Opcodes.IFLT,
        Opcodes.IFGE,
        Opcodes.IFGT,
        Opcodes.IFLE,
        Opcodes.TABLESWITCH,
        Opcodes.LOOKUPSWITCH,
        Opcodes.IRETURN,
        Opcodes.FRETURN,
        Opcodes.ARETURN,
        Opcodes.ATHROW,
        Opcodes.MONITORENTER,
        Opcodes.MONITOREXIT,
        Opcodes.IFNULL,
        Opcodes.IFNONNULL);
    set(
        -2,
        Opcodes.LSTORE,
        Opcodes.DSTORE,
        Opcodes.POP2,
        Opcodes.LADD,
        Opcodes.DADD,
        Opcodes.LSUB,
        Opcodes.DSUB,
        Opcodes.LMUL,
        Opcodes.DMUL,
        Opcodes.LDIV,
        Opcodes.DDIV,
        Opcodes.LREM,
        Opcodes.DREM,
        Opcodes.LAND,
        Opcodes.LOR,
        Opcodes.LXOR,
        Opcodes.IF_ICMPEQ,
        Opcodes.IF_ICMPNE,
        Opcodes.IF_ICMPLT,
        Opcodes.IF_ICMPGE,
        Opcodes.IF_ICMPGT,
        Opcodes.IF_ICMPLE,
        Opcodes.IF_ACMPEQ,
        Opcodes.IF_ACMPNE,
        Opcodes.LRETURN,
        Opcodes.DRETURN);
    set(
        -3,
        Opcodes.IASTORE,
        Opcodes.FASTORE,
        Opcodes.AASTORE,
        Opcodes.BASTORE,
        Opcodes.CASTORE,
        Opcodes.SASTORE,
        Opcodes.LCMP,
        Opcodes.DCMPL,
        Opcodes.DCMPG);
    set(-4, Opcodes.LASTORE, Opcodes.DASTORE);
  }

  private static void set(int delta, int... opcodes) {
    for (int opcode : opcodes) {
      DELTA[opcode] = delta;
    }
  }

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
        return DELTA[opcode];
    }
  }

  private static int invokeDelta(String descriptor, boolean noReceiver) {
    // the argument size includes one slot for the receiver
    int sizes = Type.getArgumentsAndReturnSizes(descriptor);
    return (sizes & 3) - (sizes >> 2) + (noReceiver ? 1 : 0);
  }
}
