package datadog.remoteconfig;

public class HexUtils {
  private static final int[] DEC =
      new int[] {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1, -1, -1, -1, -1, -1, -1, 10, 11, 12, 13, 14, 15, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15
      };
  private static final byte[] HEX =
      new byte[] {48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 97, 98, 99, 100, 101, 102};
  private static final char[] hex = "0123456789abcdef".toCharArray();

  private HexUtils() {}

  public static byte[] fromHexString(String input) {
    if (input == null) {
      return null;
    } else {
      int bufferSize = input.length() / 2 > 32 ? 64 : 32;
      int diff = bufferSize * 2 - input.length();
      if (diff < 0) {
        throw new IllegalArgumentException("Hex data is too large");
      } else if (diff > 0) {
        input = new String(new char[diff]).replace('\0', '0') + input;
      }
      char[] inputChars = input.toCharArray();
      byte[] result = new byte[input.length() / 2];

      for (int i = 0; i < result.length; ++i) {
        result[i] = (byte) ((getDec(inputChars[2 * i]) << 4) + getDec(inputChars[2 * i + 1]));
      }

      return result;
    }
  }

  private static int getDec(int index) {
    try {
      return DEC[index - 48];
    } catch (ArrayIndexOutOfBoundsException e) {
      return -1;
    }
  }
}
