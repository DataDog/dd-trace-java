package datadog.trace.instrumentation.kafka_clients;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// TODO - can be removed when JDK7 is dropped (adapted from JDK8 source)
public class Base64Decoder {

  private static final int[] BASE_64 = new int[256];

  static {
    Arrays.fill(BASE_64, -1);
    int i = 0;
    for (char c = 'A'; c <= 'Z'; ++c) {
      BASE_64[c] = i++;
    }
    for (char c = 'a'; c <= 'z'; ++c) {
      BASE_64[c] = i++;
    }
    for (char c = '0'; c <= '9'; ++c) {
      BASE_64[c] = i++;
    }
    BASE_64['+'] = i++;
    BASE_64['/'] = i;
    BASE_64['='] = -2;
  }

  public byte[] decode(byte[] src) {
    byte[] dst = new byte[outLength(src, 0, src.length)];
    int ret = decode0(src, 0, src.length, dst);
    if (ret != dst.length) {
      dst = Arrays.copyOf(dst, ret);
    }
    return dst;
  }

  public byte[] decode(String src) {
    return decode(src.getBytes(StandardCharsets.ISO_8859_1));
  }

  private int outLength(byte[] src, int sp, int sl) {
    int paddings = 0;
    int len = sl - sp;
    if (len == 0) return 0;
    if (len < 2) {
      if (BASE_64[0] == -1) return 0;
      throw new IllegalArgumentException(
          "Input byte[] should at least have 2 bytes for base64 bytes");
    }
    // scan all bytes to fill out all non-alphabet. a performance
    // trade-off of pre-scan or Arrays.copyOf
    int n = 0;
    while (sp < sl) {
      int b = src[sp++] & 0xff;
      if (b == '=') {
        len -= (sl - sp + 1);
        break;
      }
      if ((b = BASE_64[b]) == -1) n++;
    }
    len -= n;
    if ((len & 0x3) != 0) paddings = 4 - (len & 0x3);
    return 3 * ((len + 3) / 4) - paddings;
  }

  private int decode0(byte[] src, int sp, int sl, byte[] dst) {
    int dp = 0;
    int bits = 0;
    int shiftto = 18; // pos of first byte of 4-byte atom

    while (sp < sl) {
      if (shiftto == 18 && sp + 4 < sl) { // fast path
        int sl0 = sp + ((sl - sp) & ~0b11);
        while (sp < sl0) {
          int b1 = BASE_64[src[sp++] & 0xff];
          int b2 = BASE_64[src[sp++] & 0xff];
          int b3 = BASE_64[src[sp++] & 0xff];
          int b4 = BASE_64[src[sp++] & 0xff];
          if ((b1 | b2 | b3 | b4) < 0) { // non base64 byte
            sp -= 4;
            break;
          }
          int bits0 = b1 << 18 | b2 << 12 | b3 << 6 | b4;
          dst[dp++] = (byte) (bits0 >> 16);
          dst[dp++] = (byte) (bits0 >> 8);
          dst[dp++] = (byte) (bits0);
        }
        if (sp >= sl) break;
      }
      int b = src[sp++] & 0xff;
      if ((b = BASE_64[b]) < 0) {
        if (b == -2) { // padding byte '='
          // =     shiftto==18 unnecessary padding
          // x=    shiftto==12 a dangling single x
          // x     to be handled together with non-padding case
          // xx=   shiftto==6&&sp==sl missing last =
          // xx=y  shiftto==6 last is not =
          if (shiftto == 6 && (sp == sl || src[sp++] != '=') || shiftto == 18) {
            throw new IllegalArgumentException("Input byte array has wrong 4-byte ending unit");
          }
          break;
        }
      }
      bits |= (b << shiftto);
      shiftto -= 6;
      if (shiftto < 0) {
        dst[dp++] = (byte) (bits >> 16);
        dst[dp++] = (byte) (bits >> 8);
        dst[dp++] = (byte) (bits);
        shiftto = 18;
        bits = 0;
      }
    }
    // reached end of byte array or hit padding '=' characters.
    if (shiftto == 6) {
      dst[dp++] = (byte) (bits >> 16);
    } else if (shiftto == 0) {
      dst[dp++] = (byte) (bits >> 16);
      dst[dp++] = (byte) (bits >> 8);
    } else if (shiftto == 12) {
      // dangling single "x", incorrectly encoded.
      throw new IllegalArgumentException("Last unit does not have enough valid bits");
    }
    // ignore all non-base64 character
    while (sp < sl) {
      if (BASE_64[src[sp++] & 0xff] < 0) continue;
      throw new IllegalArgumentException("Input byte array has incorrect ending byte at " + sp);
    }
    return dp;
  }
}
