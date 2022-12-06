package datadog.trace.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

// TODO - can be removed when JDK7 is dropped (adapted from JDK source)
public final class Base64Encoder {

  private static final char[] BASE_64 = new char[65];

  static {
    int i = 0;
    for (char c = 'A'; c <= 'Z'; ++c) {
      BASE_64[i++] = c;
    }
    for (char c = 'a'; c <= 'z'; ++c) {
      BASE_64[i++] = c;
    }
    for (char c = '0'; c <= '9'; ++c) {
      BASE_64[i++] = c;
    }
    BASE_64[i++] = '+';
    BASE_64[i] = '/';
  }

  public static final Base64Encoder INSTANCE = new Base64Encoder();

  private Base64Encoder() {}

  private int outLength(int srclen) {
    int n = srclen % 3;
    return 4 * (srclen / 3) + (n == 0 ? 0 : n + 1);
  }

  private int paddedLength(int len) {
    return len == 0 ? 0 : (((len - 1) / 4) + 1) * 4;
  }

  public byte[] encode(byte[] src) {
    int len = outLength(src.length); // dst array size
    int paddedLen = paddedLength(len); // adjust for padding
    int offset = len - src.length;
    byte[] dst = new byte[paddedLen];
    System.arraycopy(src, 0, dst, offset, src.length);
    int ret = encode0(dst, offset, len, dst);
    if (ret < paddedLen) {
      Arrays.fill(dst, ret, paddedLen, (byte) '=');
    }
    return dst;
  }

  public ByteBuffer encode(ByteBuffer buffer) {
    int len = outLength(buffer.remaining()); // dst array size
    int paddedLen = paddedLength(len); // adjust for padding
    int offset = len - buffer.remaining();
    byte[] dst = new byte[paddedLen];
    int ret = 0;
    if (buffer.hasArray()) {
      System.arraycopy(
          buffer.array(),
          buffer.arrayOffset() + buffer.position(),
          dst,
          offset,
          buffer.remaining());
      ret = encode0(dst, offset, len, dst);
    } else {
      buffer.get(dst, offset, buffer.remaining());
      ret = encode0(dst, offset, len, dst);
    }
    if (ret < paddedLen) {
      Arrays.fill(dst, ret, paddedLen, (byte) '=');
    }
    return ByteBuffer.wrap(dst);
  }

  private void encodeBlock(byte[] src, int sp, int sl, byte[] dst, int dp) {
    for (int sp0 = sp, dp0 = dp; sp0 < sl; ) {
      int bits = (src[sp0++] & 0xff) << 16 | (src[sp0++] & 0xff) << 8 | (src[sp0++] & 0xff);
      dst[dp0++] = (byte) BASE_64[(bits >>> 18) & 0x3f];
      dst[dp0++] = (byte) BASE_64[(bits >>> 12) & 0x3f];
      dst[dp0++] = (byte) BASE_64[(bits >>> 6) & 0x3f];
      dst[dp0++] = (byte) BASE_64[bits & 0x3f];
    }
  }

  private int encode0(byte[] src, int off, int end, byte[] dst) {
    int sp = off;
    int slen = (end - off) / 3 * 3;
    int sl = off + slen;
    int dp = 0;
    while (sp < sl) {
      int sl0 = Math.min(sp + slen, sl);
      encodeBlock(src, sp, sl0, dst, dp);
      int dlen = (sl0 - sp) / 3 * 4;
      dp += dlen;
      sp = sl0;
    }
    if (sp < end) { // 1 or 2 leftover bytes
      int b0 = src[sp++] & 0xff;
      dst[dp++] = (byte) BASE_64[b0 >> 2];
      if (sp == end) {
        dst[dp++] = (byte) BASE_64[(b0 << 4) & 0x3f];
      } else {
        int b1 = src[sp++] & 0xff;
        dst[dp++] = (byte) BASE_64[(b0 << 4) & 0x3f | (b1 >> 4)];
        dst[dp++] = (byte) BASE_64[(b1 << 2) & 0x3f];
      }
    }
    return dp;
  }
}
