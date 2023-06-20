package datadog.trace.civisibility.ipc;

public abstract class ByteUtils {

  public static void putLong(byte[] b, int offset, long l) {
    for (int i = 0; i < Long.BYTES; i++) {
      b[offset + Long.BYTES - i - 1] = (byte) (l >> (i * 8));
    }
  }

  public static long getLong(byte[] b, int offset) {
    long l = 0;
    for (int i = 0; i < Long.BYTES; i++) {
      l |= ((long) b[offset + Long.BYTES - i - 1] & 0xff) << (i * 8);
    }
    return l;
  }

  public static void putShort(byte[] b, int offset, short s) {
    for (int i = 0; i < Short.BYTES; i++) {
      b[offset + Short.BYTES - i - 1] = (byte) (s >> (i * 8));
    }
  }

  public static short getShort(byte[] b, int offset) {
    short s = 0;
    for (int i = 0; i < Short.BYTES; i++) {
      s |= ((short) b[offset + Short.BYTES - i - 1] & 0xff) << (i * 8);
    }
    return s;
  }
}
