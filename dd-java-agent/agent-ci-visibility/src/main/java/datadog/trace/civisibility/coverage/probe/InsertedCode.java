package datadog.trace.civisibility.coverage.probe;

import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.LDC;
import static org.objectweb.asm.Opcodes.SIPUSH;

import java.util.Arrays;

/**
 * Instructions to insert into a method, with labels between them. The length of each instruction is
 * known when it is added, so a single pass over a method lays out all of its code.
 */
final class InsertedCode {

  private static final int OPCODE = 0;
  private static final int PUSH = 1;
  private static final int VARIABLE = 2;
  private static final int MEMBER = 3;
  private static final int JUMP = 4;
  private static final int LABEL = 5;

  /** Items of three ints: a kind and two operands. */
  private int[] items = new int[24];

  private int size;

  private void add(int kind, int first, int second) {
    if (size + 3 > items.length) {
      items = Arrays.copyOf(items, items.length * 2);
    }
    items[size++] = kind;
    items[size++] = first;
    items[size++] = second;
  }

  /** An instruction without operands. */
  void opcode(int opcode) {
    add(OPCODE, opcode, 0);
  }

  /**
   * Pushes a non-negative int.
   *
   * @param constant the index of an Integer constant with the value, used if the value is too large
   *     for SIPUSH
   */
  void push(int value, int constant) {
    add(PUSH, value, constant);
  }

  /** A load or store of a local variable. */
  void variable(int opcode, int local) {
    add(VARIABLE, opcode, local);
  }

  /** An instruction with a constant pool index operand, such as a field access or a call. */
  void member(int opcode, int constant) {
    add(MEMBER, opcode, constant);
  }

  /** A jump with a 2-byte offset. */
  void jump(int opcode, int label) {
    add(JUMP, opcode, label);
  }

  void label(int label) {
    add(LABEL, label, 0);
  }

  /**
   * Lays out the code from {@code offset} on and sets the offsets of its labels.
   *
   * @return the offset right after the code
   */
  int layout(int offset, int[] labelOffsets) {
    for (int k = 0; k < size; k += 3) {
      if (items[k] == LABEL) {
        labelOffsets[items[k + 1]] = offset;
      } else {
        offset += length(k);
      }
    }
    return offset;
  }

  private int length(int k) {
    switch (items[k]) {
      case OPCODE:
        return 1;
      case PUSH:
        int value = items[k + 1];
        if (value <= 5) {
          return 1;
        } else if (value <= Byte.MAX_VALUE) {
          return 2;
        } else if (value <= Short.MAX_VALUE) {
          return 3;
        }
        return items[k + 2] < 256 ? 2 : 3;
      case VARIABLE:
        int local = items[k + 2];
        return local < 4 ? 1 : local < 256 ? 2 : 4;
      case MEMBER:
      case JUMP:
        return 3;
      default:
        return 0;
    }
  }

  /** Writes the code, laid out from {@code offset} on. */
  void write(ByteArrayBuilder out, int offset, int[] labelOffsets) {
    for (int k = 0; k < size; k += 3) {
      int first = items[k + 1];
      int second = items[k + 2];
      switch (items[k]) {
        case OPCODE:
          out.putByte(first);
          break;
        case PUSH:
          if (first <= 5) {
            out.putByte(ICONST_0 + first);
          } else if (first <= Byte.MAX_VALUE) {
            out.putByte(BIPUSH).putByte(first);
          } else if (first <= Short.MAX_VALUE) {
            out.putByte(SIPUSH).putShort(first);
          } else if (second < 256) {
            out.putByte(LDC).putByte(second);
          } else {
            out.putByte(InstructionSet.LDC_W).putShort(second);
          }
          break;
        case VARIABLE:
          if (second < 4) {
            out.putByte(InstructionSet.shortVarOpcode(first, second));
          } else if (second < 256) {
            out.putByte(first).putByte(second);
          } else {
            out.putByte(InstructionSet.WIDE).putByte(first).putShort(second);
          }
          break;
        case MEMBER:
          out.putByte(first).putShort(second);
          break;
        case JUMP:
          out.putByte(first).putShort(labelOffsets[second] - offset);
          break;
        default:
          break;
      }
      offset += length(k);
    }
  }
}
