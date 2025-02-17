package datadog.trace.api;

/**
 * Represents a bitfield-based trace source for tracking product propagation tags.
 *
 * <p>The bitfield is encoded as an **8-bit hexadecimal mask** (ranging from `00` to `FF`), where
 * each bit corresponds to a specific product. This class provides utility methods for updating,
 * checking, and parsing these product bitfields.
 *
 * <ul>
 *   <li>MUST use a two-character, case-insensitive hexadecimal string (e.g., "00" to "FF").
 *   <li>MUST support parsing masks of at least 32 bits to ensure forward compatibility.
 *   <li>Each bit corresponds to a specific product:
 * </ul>
 */
public class ProductTraceSource {

  public static final int UNSET = 0;

  public static final int APM = 0x01;
  public static final int ASM = 0x02;
  public static final int DSM = 0x04;
  public static final int DJM = 0x08;
  public static final int DBM = 0x10;

  /** Updates the bitfield by setting the bit corresponding to a specific product. */
  public static int updateProduct(int bitfield, int product) {
    return bitfield |= product; // Set the bit for the given product
  }

  /** Checks if the bitfield is marked for a specific product. */
  public static boolean isProductMarked(final int bitfield, int product) {
    return (bitfield & product) != 0; // Check if the bit is set
  }

  /**
   * Converts the current bitfield to a two-character hexadecimal string.
   *
   * <p>This method ensures the output follows the **00 to FF** format, padding with leading zeros
   * if necessary.
   */
  public static String getBitfieldHex(final int bitfield) {
    String hex = Integer.toHexString(bitfield & 0xFF);
    return hex.length() == 1 ? "0" + hex : hex; // Ensure two characters
  }

  /**
   * Parses a hexadecimal string back into an integer bitfield.
   *
   * <p>This method allows for **at least 32-bit parsing**, ensuring forward compatibility with
   * potential future expansions.
   */
  public static int parseBitfieldHex(final String hexString) {
    if (hexString == null || hexString.isEmpty()) {
      return 0; // Return 0 if the string is empty
    }
    // Need to support unsigned parsing
    return (int) Long.parseUnsignedLong(hexString, 16); // Parse the string as a base-16 number
  }
}
