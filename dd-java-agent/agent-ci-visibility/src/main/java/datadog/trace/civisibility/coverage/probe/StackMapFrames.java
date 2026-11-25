package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.MALFORMED;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.UNKNOWN_FRAME;
import static org.objectweb.asm.Opcodes.NEW;

import java.util.Arrays;

/**
 * Stack map frames as full frames. A verification type is packed in an int: its class file tag in
 * the high half and its constant pool index or code offset in the low half. A long or a double
 * takes one entry, as in the class file.
 */
final class StackMapFrames {

  static final int TOP = 0;
  static final int INTEGER = 1 << 16;
  static final int FLOAT = 2 << 16;
  static final int DOUBLE = 3 << 16;
  static final int LONG = 4 << 16;
  static final int NULL = 5 << 16;
  static final int UNINITIALIZED_THIS = 6 << 16;
  static final int OBJECT_TAG = 7;
  static final int UNINITIALIZED_TAG = 8;

  static final int[] NONE = new int[0];

  private StackMapFrames() {}

  /** An object of the class named by a class constant. */
  static int object(int classConstant) {
    return OBJECT_TAG << 16 | classConstant;
  }

  static int tag(int type) {
    return type >>> 16;
  }

  /**
   * The class constant of an object type, or the code offset of the NEW of an uninitialized one.
   */
  static int payload(int type) {
    return type & 0xFFFF;
  }

  static boolean isWide(int type) {
    return type == LONG || type == DOUBLE;
  }

  /** The number of local variable or operand stack slots the types take. */
  static int slots(int[] types) {
    int slots = 0;
    for (int type : types) {
      slots += isWide(type) ? 2 : 1;
    }
    return slots;
  }

  /**
   * Reads the method's StackMapTable into the full frame at each instruction that has one.
   *
   * @param entryLocals the locals of the implicit frame at the method's entry
   * @param locals receives the locals of the frame at each instruction
   * @param stacks receives the operand stack of the frame at each instruction
   */
  static void read(MethodCode code, int[] entryLocals, int[][] locals, int[][] stacks) {
    ClassFile classFile = code.classFile;
    int at = code.stackMapTable;
    int entries = classFile.readUnsignedShort(at);
    at += 2;
    int[] current = entryLocals;
    int offset = -1;
    for (int k = 0; k < entries; k++) {
      int frameType = classFile.readByte(at++);
      int delta;
      int[] stack = NONE;
      if (frameType < 64) {
        delta = frameType;
      } else if (frameType < 128) {
        delta = frameType - 64;
        stack = new int[1];
        at = readType(code, at, stack, 0);
      } else if (frameType < 247) {
        throw new UnsupportedClassException(UNKNOWN_FRAME);
      } else {
        delta = classFile.readUnsignedShort(at);
        at += 2;
        if (frameType == 247) {
          stack = new int[1];
          at = readType(code, at, stack, 0);
        } else if (frameType < 251) {
          int chopped = 251 - frameType;
          if (chopped > current.length) {
            throw new UnsupportedClassException(MALFORMED);
          }
          current = Arrays.copyOf(current, current.length - chopped);
        } else if (frameType > 251 && frameType < 255) {
          int[] appended = Arrays.copyOf(current, current.length + frameType - 251);
          for (int j = current.length; j < appended.length; j++) {
            at = readType(code, at, appended, j);
          }
          current = appended;
        } else if (frameType == 255) {
          current = new int[classFile.readUnsignedShort(at)];
          at += 2;
          for (int j = 0; j < current.length; j++) {
            at = readType(code, at, current, j);
          }
          stack = new int[classFile.readUnsignedShort(at)];
          at += 2;
          for (int j = 0; j < stack.length; j++) {
            at = readType(code, at, stack, j);
          }
        }
      }
      offset += delta + 1;
      if (offset >= code.codeLength || code.indexAt[offset] < 0) {
        throw new UnsupportedClassException(MALFORMED);
      }
      int i = code.indexAt[offset];
      locals[i] = current;
      stacks[i] = stack;
    }
  }

  private static int readType(MethodCode code, int at, int[] types, int index) {
    ClassFile classFile = code.classFile;
    int tag = classFile.readByte(at);
    if (tag < OBJECT_TAG) {
      types[index] = tag << 16;
      return at + 1;
    } else if (tag > UNINITIALIZED_TAG) {
      throw new UnsupportedClassException(UNKNOWN_FRAME);
    }
    int payload = classFile.readUnsignedShort(at + 1);
    if (tag == UNINITIALIZED_TAG
        && (payload >= code.codeLength
            || code.indexAt[payload] < 0
            || code.rawOpcode(code.indexAt[payload]) != NEW)) {
      throw new UnsupportedClassException(MALFORMED);
    }
    types[index] = tag << 16 | payload;
    return at + 3;
  }

  /**
   * Writes a verification type.
   *
   * @param newOffsets the new code offset of each instruction, for the NEW of uninitialized types
   */
  static void writeType(ByteArrayBuilder out, int type, MethodCode code, int[] newOffsets) {
    int tag = tag(type);
    out.putByte(tag);
    if (tag == OBJECT_TAG) {
      out.putShort(payload(type));
    } else if (tag == UNINITIALIZED_TAG) {
      out.putShort(newOffsets[code.indexAt[payload(type)]]);
    }
  }
}
