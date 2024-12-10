package datadog.trace.bootstrap.instrumentation.spanpointers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class SpanPointersHelper {
  /**
   * Generates a unique hash from an array of strings by joining them with | before hashing. Used to
   * uniquely identify AWS requests for span pointers.
   *
   * @param components Array of strings to hash
   * @return A 32-character hash uniquely identifying the components
   * @throws NoSuchAlgorithmException this should never happen; but should be handled just in case.
   */
  public static String generatePointerHash(String[] components) throws NoSuchAlgorithmException {
    byte[] hash =
        MessageDigest.getInstance("SHA-256")
            .digest(String.join("|", components).getBytes(StandardCharsets.UTF_8));

    StringBuilder hex = new StringBuilder(32);
    for (int i = 0; i < 16; i++) {
      hex.append(String.format("%02x", hash[i]));
    }

    return hex.toString();
  }
}
