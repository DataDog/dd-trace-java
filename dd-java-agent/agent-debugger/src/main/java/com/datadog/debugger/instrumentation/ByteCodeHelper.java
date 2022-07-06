package com.datadog.debugger.instrumentation;

import java.util.Arrays;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Helper class for bytecode analysis */
public class ByteCodeHelper {

  /**
   * @return positive integer for number of slots that are pushed onto the stack and negative
   *     integer for slot consumed from the stack see
   *     https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-6.html#jvms-6.5 for reference
   */
  public static int adjustStackUsage(AbstractInsnNode currentInsn) {
    switch (currentInsn.getType()) {
      case AbstractInsnNode.INSN:
        return adjustStackUsageInsn((InsnNode) currentInsn);
      case AbstractInsnNode.INT_INSN:
        return adjustStackUsageIntInsn((IntInsnNode) currentInsn);
      case AbstractInsnNode.VAR_INSN:
        return adjustStackUsageVarInsn((VarInsnNode) currentInsn);
      case AbstractInsnNode.TYPE_INSN:
        return adjustStackUsageTypeInsn((TypeInsnNode) currentInsn);
      case AbstractInsnNode.FIELD_INSN:
        return adjustStackUsageFieldInsn((FieldInsnNode) currentInsn);
      case AbstractInsnNode.METHOD_INSN:
        return adjustStackUsageMethodInsn((MethodInsnNode) currentInsn);
      case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
        return adjustStackUsageInvokeDynamicInsn((InvokeDynamicInsnNode) currentInsn);
      case AbstractInsnNode.JUMP_INSN:
        return adjustStackUsageJumpInsn((JumpInsnNode) currentInsn);
      case AbstractInsnNode.LDC_INSN:
        return adjustStackUsageLdcInsn((LdcInsnNode) currentInsn);
      case AbstractInsnNode.IINC_INSN:
        return adjustStackUsageIincInsn((IincInsnNode) currentInsn);
      case AbstractInsnNode.TABLESWITCH_INSN:
        return adjustStackUsageTableSwitchInsn((TableSwitchInsnNode) currentInsn);
      case AbstractInsnNode.LOOKUPSWITCH_INSN:
        return adjustStackUsageLookupSwitchInsn((LookupSwitchInsnNode) currentInsn);
      case AbstractInsnNode.MULTIANEWARRAY_INSN:
        return adjustStackUsageMultiANewArrayInsn((MultiANewArrayInsnNode) currentInsn);
    }
    return 0;
  }

  private static int adjustStackUsageInsn(InsnNode currentInsn) {
    switch (currentInsn.getOpcode()) {
      case Opcodes.ACONST_NULL:
      case Opcodes.ICONST_M1:
      case Opcodes.ICONST_0:
      case Opcodes.ICONST_1:
      case Opcodes.ICONST_2:
      case Opcodes.ICONST_3:
      case Opcodes.ICONST_4:
      case Opcodes.ICONST_5:
      case Opcodes.FCONST_0:
      case Opcodes.FCONST_1:
      case Opcodes.FCONST_2:
        return 1;
      case Opcodes.LCONST_0:
      case Opcodes.LCONST_1:
      case Opcodes.DCONST_0:
      case Opcodes.DCONST_1:
        return 2;
      case Opcodes.IALOAD:
      case Opcodes.FALOAD:
      case Opcodes.AALOAD:
      case Opcodes.BALOAD:
      case Opcodes.CALOAD:
      case Opcodes.SALOAD:
        // consume array ref and index from the stack
        // push value onto the stack
        return -2 + 1;
      case Opcodes.IASTORE:
      case Opcodes.FASTORE:
      case Opcodes.AASTORE:
      case Opcodes.BASTORE:
      case Opcodes.CASTORE:
      case Opcodes.SASTORE:
        // consume array ref, index and value from the stack
        return -3;
      case Opcodes.LASTORE:
      case Opcodes.DASTORE:
        // consume array ref, index and long value from the stack
        return -4;
      case Opcodes.POP:
      case Opcodes.IRETURN:
      case Opcodes.FRETURN:
      case Opcodes.ARETURN:
      case Opcodes.MONITORENTER:
      case Opcodes.MONITOREXIT:
        // consumes 1 slot from the stack
        return -1;
      case Opcodes.POP2:
      case Opcodes.LRETURN:
      case Opcodes.DRETURN:
        // consumes 2 slots from the stack
        return -2;
      case Opcodes.DUP:
      case Opcodes.DUP_X1:
      case Opcodes.DUP_X2:
        // push 1 slot onto the stack
        return 1;
      case Opcodes.DUP2:
      case Opcodes.DUP2_X1:
      case Opcodes.DUP2_X2:
        // push 2 slots onto the stack
        return 2;
      case Opcodes.SWAP:
      case Opcodes.INEG:
      case Opcodes.FNEG:
      case Opcodes.I2F:
      case Opcodes.F2I:
      case Opcodes.I2B:
      case Opcodes.I2C:
      case Opcodes.I2S:
        // consume 1 slot from the stack
        // push 1 slot to the stack
        return -1 + 1;
      case Opcodes.LNEG:
      case Opcodes.DNEG:
      case Opcodes.L2D:
      case Opcodes.D2L:
        // consume 2 slots from the stack
        // push 2 slots to the stack
        return -2 + 2;
      case Opcodes.NOP:
      case Opcodes.RETURN:
      case Opcodes.ARRAYLENGTH:
      case Opcodes.ATHROW:
        // no change
        return 0;
      case Opcodes.IADD:
      case Opcodes.FADD:
      case Opcodes.ISUB:
      case Opcodes.FSUB:
      case Opcodes.IMUL:
      case Opcodes.FMUL:
      case Opcodes.IDIV:
      case Opcodes.FDIV:
      case Opcodes.IREM:
      case Opcodes.FREM:
      case Opcodes.ISHL:
      case Opcodes.ISHR:
      case Opcodes.IUSHR:
      case Opcodes.IAND:
      case Opcodes.IOR:
      case Opcodes.IXOR:
      case Opcodes.FCMPL:
      case Opcodes.FCMPG:
        // consume 2 slots from the stack
        // push the result onto the stack
        return -2 + 1;
      case Opcodes.LADD:
      case Opcodes.DADD:
      case Opcodes.LSUB:
      case Opcodes.DSUB:
      case Opcodes.LMUL:
      case Opcodes.DMUL:
      case Opcodes.LDIV:
      case Opcodes.DDIV:
      case Opcodes.LREM:
      case Opcodes.DREM:
      case Opcodes.LAND:
      case Opcodes.LOR:
      case Opcodes.LXOR:
        // consume 2x 2 slots from the stack
        // push the result onto the stack
        return -4 + 2;
      case Opcodes.LSHL:
      case Opcodes.LSHR:
      case Opcodes.LUSHR:
        // consume 2(long)+1(int) slots from the stack
        // push the result onto the stack
        return -3 + 2;
      case Opcodes.I2L:
      case Opcodes.I2D:
      case Opcodes.F2L:
      case Opcodes.F2D:
        // consume 1 slot from the stack
        // push 2 slots (long) onto the stack
        return -1 + 2;
      case Opcodes.L2I:
      case Opcodes.L2F:
      case Opcodes.D2I:
      case Opcodes.D2F:
        // consume 2 slots (long) from the stack
        // push 1 slot onto the stack
        return -2 + 1;
      case Opcodes.LCMP:
      case Opcodes.DCMPL:
      case Opcodes.DCMPG:
        // consume 2x 2 slots from the stack
        // push the 1 slot result onto the stack
        return -4 + 1;
    }
    return 0;
  }

  private static int adjustStackUsageIntInsn(IntInsnNode currentInsn) {
    switch (currentInsn.getOpcode()) {
      case Opcodes.BIPUSH:
      case Opcodes.SIPUSH:
        return 1;
      case Opcodes.NEWARRAY:
        // consumes count from the stack
        // pushes a new instance on the stack
        return -1 + 1;
    }
    return 0;
  }

  private static int adjustStackUsageVarInsn(VarInsnNode currentInsn) {
    switch (currentInsn.getOpcode()) {
      case Opcodes.ILOAD:
      case Opcodes.FLOAD:
      case Opcodes.ALOAD:
        return 1;
      case Opcodes.LLOAD:
      case Opcodes.DLOAD:
        return 2;
      case Opcodes.ISTORE:
      case Opcodes.FSTORE:
      case Opcodes.ASTORE:
        return -1;
      case Opcodes.LSTORE:
      case Opcodes.DSTORE:
        return -2;
      case Opcodes.RET:
    }
    return 0;
  }

  private static int adjustStackUsageTypeInsn(TypeInsnNode currentInsn) {
    switch (currentInsn.getOpcode()) {
      case Opcodes.NEW:
        return 1;
      case Opcodes.ANEWARRAY:
        // consumes count from the stack
        // pushes a new instance on the stack
        return -1 + 1;
      case Opcodes.CHECKCAST:
        // no change on the stack
        return 0;
      case Opcodes.INSTANCEOF:
        // consumes object reference from the stack
        // pushes result onto the stack
        return -1 + 1;
    }
    return 0;
  }

  private static int adjustStackUsageFieldInsn(FieldInsnNode currentInsn) {
    Type type = Type.getType(((FieldInsnNode) currentInsn).desc);
    switch (currentInsn.getOpcode()) {
      case Opcodes.GETFIELD:
        return type.getSize() - 1; // consume instance field
      case Opcodes.PUTFIELD:
        return -type.getSize() - 1; // consume instance field
      case Opcodes.GETSTATIC:
        return type.getSize();
      case Opcodes.PUTSTATIC:
        return -type.getSize();
    }
    return 0;
  }

  private static int adjustStackUsageMethodInsn(MethodInsnNode currentInsn) {
    Type methodType = Type.getType(currentInsn.desc);
    int argumentSize = Arrays.stream(methodType.getArgumentTypes()).mapToInt(Type::getSize).sum();
    int returnSize = methodType.getReturnType().getSize();
    switch (currentInsn.getOpcode()) {
      case Opcodes.INVOKESTATIC:
        // consumes arguments
        // push return value
        return -argumentSize + returnSize;
      case Opcodes.INVOKESPECIAL:
      case Opcodes.INVOKEVIRTUAL:
      case Opcodes.INVOKEINTERFACE:
        // consume this
        // consume arguments
        // push return value
        return -1 - argumentSize + returnSize;
    }
    return 0;
  }

  private static int adjustStackUsageInvokeDynamicInsn(InvokeDynamicInsnNode currentInsn) {
    Type methodType = Type.getType(currentInsn.desc);
    int argumentSize = Arrays.stream(methodType.getArgumentTypes()).mapToInt(Type::getSize).sum();
    int returnSize = methodType.getReturnType().getSize();
    // consume arguments
    // push return value
    return -argumentSize + returnSize;
  }

  private static int adjustStackUsageJumpInsn(JumpInsnNode currentInsn) {
    switch (currentInsn.getOpcode()) {
      case Opcodes.IFEQ:
      case Opcodes.IFNE:
      case Opcodes.IFLT:
      case Opcodes.IFGE:
      case Opcodes.IFGT:
      case Opcodes.IFLE:
      case Opcodes.IFNULL:
      case Opcodes.IFNONNULL:
        // consume value from the stack
        return -1;
      case Opcodes.IF_ICMPEQ:
      case Opcodes.IF_ICMPNE:
      case Opcodes.IF_ICMPLT:
      case Opcodes.IF_ICMPGE:
      case Opcodes.IF_ICMPGT:
      case Opcodes.IF_ICMPLE:
      case Opcodes.IF_ACMPEQ:
      case Opcodes.IF_ACMPNE:
        // consume 2 values from the stack
        return -2;
      case Opcodes.JSR:
        return 1;
      case Opcodes.GOTO:
        // no change
    }
    return 0;
  }

  private static int adjustStackUsageLdcInsn(LdcInsnNode currentInsn) {
    // LDC, LDC_W, LDC2_W
    Object cst = currentInsn.cst;
    if (cst instanceof Long || cst instanceof Double) {
      return 2;
    }
    return 1;
  }

  private static int adjustStackUsageIincInsn(IincInsnNode currentInsn) {
    // no change
    return 0;
  }

  private static int adjustStackUsageTableSwitchInsn(TableSwitchInsnNode currentInsn) {
    // consumes 1 slot from the stack (index)
    return -1;
  }

  private static int adjustStackUsageLookupSwitchInsn(LookupSwitchInsnNode currentInsn) {
    // consumes 1 slot from the stack (key)
    return -1;
  }

  private static int adjustStackUsageMultiANewArrayInsn(MultiANewArrayInsnNode currentInsn) {
    // pushes one instance on the stack (arrayref)
    // consumes dims item from the stack
    return 1 - currentInsn.dims;
  }
}
