package datadog.remoteconfig;

import java.util.Arrays;

public class HexUtils {
  private static final int[] DEC =
      new int[] {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1, -1, -1, -1, -1, -1, -1, 10, 11, 12, 13, 14, 15, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15
      };

  private HexUtils() {}

  // only accepts 128 or 256 bit input
  public static byte[] fromHexString(String input) {
    if (input == null) {
      return null;
    }

    int bufferSize = input.length() / 2 > 32 ? 64 : 32;
    int diff = bufferSize * 2 - input.length();
    char[] inputChars;
    if (diff < 0) {
      throw new IllegalArgumentException("Hex data is too large");
    } else if (diff > 0) {
      inputChars = new char[diff + input.length()];
      Arrays.fill(inputChars, 0, diff, '0');
      System.arraycopy(input.toCharArray(), 0, inputChars, diff, input.length());
    } else {
      inputChars = input.toCharArray();
    }
    byte[] result = new byte[inputChars.length / 2];

    for (int i = 0; i < result.length; ++i) {
      result[i] = (byte) ((getDec(inputChars[2 * i]) << 4) + getDec(inputChars[2 * i + 1]));
    }

    return result;
  }

  private static int getDec(int index) {
    int lookupIdx = index - 48;
    if (lookupIdx < 0 || lookupIdx >= DEC.length) {
      return -1;
    }
    return DEC[lookupIdx];
  }
}
