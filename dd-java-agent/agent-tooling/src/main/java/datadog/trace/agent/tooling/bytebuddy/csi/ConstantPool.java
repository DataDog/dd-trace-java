package datadog.trace.agent.tooling.bytebuddy.csi;

import javax.annotation.Nonnull;

public class ConstantPool {

  public static final int CONSTANT_CLASS_TAG = 7;
  public static final int CONSTANT_FIELDREF_TAG = 9;
  public static final int CONSTANT_METHODREF_TAG = 10;
  public static final int CONSTANT_INTERFACE_METHODREF_TAG = 11;
  public static final int CONSTANT_STRING_TAG = 8;
  public static final int CONSTANT_INTEGER_TAG = 3;
  public static final int CONSTANT_FLOAT_TAG = 4;
  public static final int CONSTANT_LONG_TAG = 5;
  public static final int CONSTANT_DOUBLE_TAG = 6;
  public static final int CONSTANT_NAME_AND_TYPE_TAG = 12;
  public static final int CONSTANT_UTF8_TAG = 1;
  public static final int CONSTANT_METHOD_HANDLE_TAG = 15;
  public static final int CONSTANT_METHOD_TYPE_TAG = 16;
  public static final int CONSTANT_DYNAMIC_TAG = 17;
  public static final int CONSTANT_INVOKE_DYNAMIC_TAG = 18;
  public static final int CONSTANT_MODULE_TAG = 19;
  public static final int CONSTANT_PACKAGE_TAG = 20;

  private static final int CONSTANT_POOL_START = 8;

  private final byte[] classBuffer;
  private final int[] offsets;
  private final int count;
  private final char[] charBuffer;

  public ConstantPool(@Nonnull final byte[] classBuffer) {
    this.classBuffer = classBuffer;
    count = readUnsignedShort(CONSTANT_POOL_START);
    offsets = new int[count];
    final int maxStringLength = initConstantPool(count);
    charBuffer = new char[maxStringLength];
  }

  private int initConstantPool(final int cpInfoCount) {
    int cpInfoOffset = CONSTANT_POOL_START + 2;
    int cpInfoIndex = 1;
    int maxStringLength = 0;
    while (cpInfoIndex < cpInfoCount) {
      final int type = classBuffer[cpInfoOffset++];
      offsets[cpInfoIndex] = cpInfoOffset;
      cpInfoIndex += 1;
      switch (type) {
        case CONSTANT_LONG_TAG:
        case CONSTANT_DOUBLE_TAG:
          cpInfoOffset += 8;
          cpInfoIndex += 1;
          break;
        case CONSTANT_METHODREF_TAG:
        case CONSTANT_INTERFACE_METHODREF_TAG:
        case CONSTANT_FIELDREF_TAG:
        case CONSTANT_INTEGER_TAG:
        case CONSTANT_FLOAT_TAG:
        case CONSTANT_NAME_AND_TYPE_TAG:
        case CONSTANT_DYNAMIC_TAG:
        case CONSTANT_INVOKE_DYNAMIC_TAG:
          cpInfoOffset += 4;
          break;
        case CONSTANT_UTF8_TAG:
          final int strSize = readUnsignedShort(cpInfoOffset);
          cpInfoOffset += 2;
          cpInfoOffset += strSize;
          if (strSize > maxStringLength) {
            maxStringLength = strSize;
          }
          break;
        case CONSTANT_METHOD_HANDLE_TAG:
          cpInfoOffset += 3;
          break;
        case CONSTANT_CLASS_TAG:
        case CONSTANT_STRING_TAG:
        case CONSTANT_METHOD_TYPE_TAG:
        case CONSTANT_PACKAGE_TAG:
        case CONSTANT_MODULE_TAG:
          cpInfoOffset += 2;
          break;
        default:
          throw new IllegalArgumentException();
      }
    }
    return maxStringLength;
  }

  public int getCount() {
    return count;
  }

  public int getType(final int index) {
    final int offset = offsets[index] - 1;
    if (offset < 0) {
      return -1; // can happen for long and double (they take two spots in the constant pool)
    }
    return classBuffer[offset];
  }

  public int getOffset(final int index) {
    return offsets[index];
  }

  public int readUnsignedShort(final int offset) {
    return ((classBuffer[offset] & 0xFF) << 8) | (classBuffer[offset + 1] & 0xFF);
  }

  public String readUTF8(final int offset) {
    final int length = readUnsignedShort(offset);
    int currentOffset = offset + 2;
    int endOffset = currentOffset + length;
    int strLength = 0;
    while (currentOffset < endOffset) {
      int currentByte = classBuffer[currentOffset++];
      if ((currentByte & 0x80) == 0) {
        charBuffer[strLength++] = (char) (currentByte & 0x7F);
      } else if ((currentByte & 0xE0) == 0xC0) {
        charBuffer[strLength++] =
            (char) (((currentByte & 0x1F) << 6) + (classBuffer[currentOffset++] & 0x3F));
      } else {
        charBuffer[strLength++] =
            (char)
                (((currentByte & 0xF) << 12)
                    + ((classBuffer[currentOffset++] & 0x3F) << 6)
                    + (classBuffer[currentOffset++] & 0x3F));
      }
    }
    return new String(charBuffer, 0, strLength);
  }
}
