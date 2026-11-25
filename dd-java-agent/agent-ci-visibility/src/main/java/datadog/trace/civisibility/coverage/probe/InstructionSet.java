package datadog.trace.civisibility.coverage.probe;

import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.JSR;
import static org.objectweb.asm.Opcodes.LDC;

import java.util.Arrays;
import org.objectweb.asm.Opcodes;

/** Lengths of the JVM's instructions, and the opcodes ASM's tree API gives them. */
final class InstructionSet {

  static final int LDC_W = 19;
  static final int LDC2_W = 20;
  static final int WIDE = 196;
  static final int GOTO_W = 200;
  static final int JSR_W = 201;

  /** Instruction length by opcode: 0 for the switches and wide, -1 for a byte that is no opcode. */
  private static final byte[] LENGTH = new byte[256];

  static {
    Arrays.fill(LENGTH, (byte) -1);
    fill(0, 15, 1); // nop to dconst_1
    fill(16, 16, 2); // bipush
    fill(17, 17, 3); // sipush
    fill(18, 18, 2); // ldc
    fill(19, 20, 3); // ldc_w, ldc2_w
    fill(21, 25, 2); // iload to aload
    fill(26, 53, 1); // iload_0 to saload
    fill(54, 58, 2); // istore to astore
    fill(59, 131, 1); // istore_0 to lxor
    fill(132, 132, 3); // iinc
    fill(133, 152, 1); // i2l to dcmpg
    fill(153, 168, 3); // ifeq to jsr
    fill(169, 169, 2); // ret
    fill(170, 171, 0); // tableswitch, lookupswitch
    fill(172, 177, 1); // ireturn to return
    fill(178, 184, 3); // getstatic to invokestatic
    fill(185, 186, 5); // invokeinterface, invokedynamic
    fill(187, 187, 3); // new
    fill(188, 188, 2); // newarray
    fill(189, 189, 3); // anewarray
    fill(190, 191, 1); // arraylength, athrow
    fill(192, 193, 3); // checkcast, instanceof
    fill(194, 195, 1); // monitorenter, monitorexit
    fill(196, 196, 0); // wide
    fill(197, 197, 4); // multianewarray
    fill(198, 199, 3); // ifnull, ifnonnull
    fill(200, 201, 5); // goto_w, jsr_w
  }

  private static void fill(int first, int last, int length) {
    for (int opcode = first; opcode <= last; opcode++) {
      LENGTH[opcode] = (byte) length;
    }
  }

  /** Operand stack size change by opcode, for instructions whose change has no operand. */
  private static final int[] STACK_DELTA = new int[256];

  static {
    setStackDelta(
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
    setStackDelta(
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
    setStackDelta(
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
    setStackDelta(
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
    setStackDelta(
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
    setStackDelta(-4, Opcodes.LASTORE, Opcodes.DASTORE);
  }

  private static void setStackDelta(int delta, int... opcodes) {
    for (int opcode : opcodes) {
      STACK_DELTA[opcode] = delta;
    }
  }

  /**
   * The operand stack size change of an instruction whose change does not depend on its operands.
   */
  static int stackDelta(int opcode) {
    return STACK_DELTA[opcode];
  }

  private InstructionSet() {}

  /** The length of an instruction with a fixed length, 0 for one without, -1 for no opcode. */
  static int fixedLength(int opcode) {
    return LENGTH[opcode];
  }

  /** The number of padding bytes after a switch opcode at {@code offset} of its code. */
  static int switchPadding(int offset) {
    return (3 - offset) & 3;
  }

  /**
   * The opcode of an instruction in ASM's tree API, which reads short and wide forms of loads,
   * stores, constants and jumps as their plain form. The opcode after a wide prefix is not resolved
   * here.
   */
  static int treeOpcode(int opcode) {
    if (opcode >= 26 && opcode <= 45) {
      return ILOAD + (opcode - 26) / 4; // iload_0 to aload_3
    } else if (opcode >= 59 && opcode <= 78) {
      return ISTORE + (opcode - 59) / 4; // istore_0 to astore_3
    } else if (opcode == LDC_W || opcode == LDC2_W) {
      return LDC;
    } else if (opcode == GOTO_W) {
      return GOTO;
    } else if (opcode == JSR_W) {
      return JSR;
    }
    return opcode;
  }

  /** Whether the opcode is a jump with a 2-byte offset. */
  static boolean isShortJump(int opcode) {
    return (opcode >= 153 && opcode <= 168) || opcode == 198 || opcode == 199;
  }

  /** The opcode of the one-byte form of a load or store of a local below 4. */
  static int shortVarOpcode(int opcode, int local) {
    return opcode < ISTORE ? 26 + (opcode - ILOAD) * 4 + local : 59 + (opcode - ISTORE) * 4 + local;
  }
}
