package datadog.trace.civisibility.coverage.probe;

/**
 * A class file read in place: the offsets of its constant pool entries and of its members, and
 * readers for the values stored at an offset. Nothing is decoded until asked for.
 */
final class ClassFile {

  static final int CONSTANT_UTF8 = 1;
  static final int CONSTANT_INTEGER = 3;
  static final int CONSTANT_FLOAT = 4;
  static final int CONSTANT_LONG = 5;
  static final int CONSTANT_DOUBLE = 6;
  static final int CONSTANT_CLASS = 7;
  static final int CONSTANT_STRING = 8;
  static final int CONSTANT_FIELDREF = 9;
  static final int CONSTANT_METHODREF = 10;
  static final int CONSTANT_INTERFACE_METHODREF = 11;
  static final int CONSTANT_NAME_AND_TYPE = 12;
  static final int CONSTANT_METHOD_HANDLE = 15;
  static final int CONSTANT_METHOD_TYPE = 16;
  static final int CONSTANT_DYNAMIC = 17;
  static final int CONSTANT_INVOKE_DYNAMIC = 18;
  static final int CONSTANT_MODULE = 19;
  static final int CONSTANT_PACKAGE = 20;

  final byte[] bytes;
  final int majorVersion;

  /** The number of constant pool slots, as in the class file: one more than the last index. */
  final int constantCount;

  /** The offset of each constant pool entry's tag; 0 for index 0 and for the slot after a long. */
  final int[] constantOffset;

  /** The offset of the access flags, right after the constant pool. */
  final int constantsEnd;

  final int access;
  final int thisClass;

  /** The offset of the methods count. */
  final int methodsOffset;

  /** The offset of the class attributes count. */
  final int attributesOffset;

  /**
   * @throws IllegalArgumentException if the bytes are not a class file this reader understands
   */
  ClassFile(byte[] bytes) {
    this.bytes = bytes;
    if (readInt(0) != 0xCAFEBABE) {
      throw new IllegalArgumentException("not a class file");
    }
    majorVersion = readUnsignedShort(6);
    constantCount = readUnsignedShort(8);
    constantOffset = new int[constantCount];
    int offset = 10;
    for (int i = 1; i < constantCount; i++) {
      constantOffset[i] = offset;
      int tag = bytes[offset] & 0xFF;
      switch (tag) {
        case CONSTANT_UTF8:
          offset += 3 + readUnsignedShort(offset + 1);
          break;
        case CONSTANT_INTEGER:
        case CONSTANT_FLOAT:
        case CONSTANT_FIELDREF:
        case CONSTANT_METHODREF:
        case CONSTANT_INTERFACE_METHODREF:
        case CONSTANT_NAME_AND_TYPE:
        case CONSTANT_DYNAMIC:
        case CONSTANT_INVOKE_DYNAMIC:
          offset += 5;
          break;
        case CONSTANT_LONG:
        case CONSTANT_DOUBLE:
          offset += 9;
          i++;
          break;
        case CONSTANT_CLASS:
        case CONSTANT_STRING:
        case CONSTANT_METHOD_TYPE:
        case CONSTANT_MODULE:
        case CONSTANT_PACKAGE:
          offset += 3;
          break;
        case CONSTANT_METHOD_HANDLE:
          offset += 4;
          break;
        default:
          throw new IllegalArgumentException("unknown constant pool tag " + tag);
      }
    }
    constantsEnd = offset;
    access = readUnsignedShort(offset);
    thisClass = readUnsignedShort(offset + 2);
    offset += 6;
    offset += 2 + 2 * readUnsignedShort(offset); // interfaces
    offset = skipMembers(offset); // fields
    methodsOffset = offset;
    attributesOffset = skipMembers(offset);
  }

  private int skipMembers(int offset) {
    int count = readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < count; i++) {
      offset = skipAttributes(offset + 6);
    }
    return offset;
  }

  /**
   * @param offset the offset of an attributes count
   * @return the offset right after those attributes
   */
  int skipAttributes(int offset) {
    int count = readUnsignedShort(offset);
    offset += 2;
    for (int i = 0; i < count; i++) {
      offset += 6 + readInt(offset + 2);
    }
    return offset;
  }

  int readByte(int offset) {
    return bytes[offset] & 0xFF;
  }

  int readUnsignedShort(int offset) {
    return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
  }

  int readShort(int offset) {
    return (short) readUnsignedShort(offset);
  }

  int readInt(int offset) {
    return ((bytes[offset] & 0xFF) << 24)
        | ((bytes[offset + 1] & 0xFF) << 16)
        | ((bytes[offset + 2] & 0xFF) << 8)
        | (bytes[offset + 3] & 0xFF);
  }

  int tag(int index) {
    return bytes[constantOffset[index]] & 0xFF;
  }

  /** Whether the UTF8 constant at {@code index} holds the given ASCII text. */
  boolean utf8Equals(int index, String ascii) {
    if (index <= 0 || index >= constantCount) {
      return false;
    }
    int offset = constantOffset[index];
    if (offset == 0 || (bytes[offset] & 0xFF) != CONSTANT_UTF8) {
      return false;
    }
    int length = readUnsignedShort(offset + 1);
    if (length != ascii.length()) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (bytes[offset + 3 + i] != ascii.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  String utf8(int index) {
    int offset = constantOffset[index];
    return decodeUtf8(offset + 3, readUnsignedShort(offset + 1));
  }

  /** The name of the class constant at {@code index}, as an internal name or array descriptor. */
  String className(int index) {
    return utf8(readUnsignedShort(constantOffset[index] + 1));
  }

  /** The UTF8 index of the name of a field or method reference. */
  int referenceName(int index) {
    int nameAndType = readUnsignedShort(constantOffset[index] + 3);
    return readUnsignedShort(constantOffset[nameAndType] + 1);
  }

  /** Decodes modified UTF-8, as stored in class files. */
  String decodeUtf8(int offset, int length) {
    char[] chars = new char[length];
    int count = 0;
    int end = offset + length;
    while (offset < end) {
      int b = bytes[offset++];
      if ((b & 0x80) == 0) {
        chars[count++] = (char) (b & 0x7F);
      } else if ((b & 0xE0) == 0xC0) {
        chars[count++] = (char) (((b & 0x1F) << 6) | (bytes[offset++] & 0x3F));
      } else {
        chars[count++] =
            (char) (((b & 0xF) << 12) | ((bytes[offset++] & 0x3F) << 6) | (bytes[offset++] & 0x3F));
      }
    }
    return new String(chars, 0, count);
  }
}
