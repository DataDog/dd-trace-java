package datadog.trace.api;

public class ProductTraceSource {

  public static final int UNSET = 0;

  public static final int APM = 0x01;
  public static final int ASM = 0x02;
  public static final int DSM = 0x04;
  public static final int DJM = 0x08;
  public static final int DBM = 0x10;

  // Update (set) the bitfield for a specific product
  public static int updateProduct(int bitfield, int product) {
    return bitfield |= product; // Set the bit for the given product
  }

  // Check if the bitfield is marked for a specific product
  public static boolean isProductMarked(final int bitfield, int product) {
    return (bitfield & product) != 0; // Check if the bit is set
  }

  // Get the current bitfield as a hexadecimal string
  public static String getBitfieldHex(final int bitfield) {
    return String.format("%02x", bitfield); // Convert to 2-character hex
  }

  // Parse a hexadecimal string back to an integer
  public static int parseBitfieldHex(final String hexString) {
    if (hexString == null || hexString.isEmpty()) {
      return 0; // Return 0 if the string is empty
    }
    return Integer.parseInt(hexString, 16); // Parse the string as a base-16 number
  }
}
