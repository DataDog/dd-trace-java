package datadog.trace.civisibility.coverage.probe;

import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.JSR;
import static org.objectweb.asm.Opcodes.LDC;

import java.util.Arrays;

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
