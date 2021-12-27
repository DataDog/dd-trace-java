package datadog.trace.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

// TODO - can be removed when JDK7 is dropped (adapted from JDK source)
public class Base64Encoder {

  protected static final char[] BASE_64 = new char[65];

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

  private final boolean doPadding;

  public Base64Encoder(boolean doPadding) {
    this.doPadding = doPadding;
  }

  private int outLength(int srclen) {
    int len = 0;
    if (doPadding) {
      len = 4 * ((srclen + 2) / 3);
    } else {
      int n = srclen % 3;
      len = 4 * (srclen / 3) + (n == 0 ? 0 : n + 1);
    }
    return len;
  }

  public byte[] encode(byte[] src) {
    int len = outLength(src.length); // dst array size
    byte[] dst = new byte[len];
    int ret = encode0(src, 0, src.length, dst);
    if (ret != dst.length) return Arrays.copyOf(dst, ret);
    return dst;
  }

  public ByteBuffer encode(ByteBuffer buffer) {
    int len = outLength(buffer.remaining());
    byte[] dst = new byte[len];
    int ret = 0;
    if (buffer.hasArray()) {
      ret =
          encode0(
              buffer.array(),
              buffer.arrayOffset() + buffer.position(),
              buffer.arrayOffset() + buffer.limit(),
              dst);
      buffer.position(buffer.limit());
    } else {
      byte[] src = new byte[buffer.remaining()];
      buffer.get(src);
      ret = encode0(src, 0, src.length, dst);
    }
    if (ret != dst.length) dst = Arrays.copyOf(dst, ret);
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
        if (doPadding) {
          dst[dp++] = '=';
          dst[dp++] = '=';
        }
      } else {
        int b1 = src[sp++] & 0xff;
        dst[dp++] = (byte) BASE_64[(b0 << 4) & 0x3f | (b1 >> 4)];
        dst[dp++] = (byte) BASE_64[(b1 << 2) & 0x3f];
        if (doPadding) {
          dst[dp++] = '=';
        }
      }
    }
    return dp;
  }
}
