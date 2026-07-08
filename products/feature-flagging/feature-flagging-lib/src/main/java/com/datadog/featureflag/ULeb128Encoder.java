package com.datadog.featureflag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * ULEB128 delta-varint + base64 codec for APM feature-flag span enrichment.
 *
 * <p>Ported VERBATIM from the frozen Node reference ({@code dd-trace-js#8343}). The tag names,
 * encoding, and golden vectors are FROZEN against that contract — backend/Trino decode and the
 * parametric system-tests assertions depend on exact parity, so this MUST NOT be re-derived.
 *
 * <p>Algorithm: dedupe (via {@link Set}) → sort ascending → emit the delta from the previous id as
 * an unsigned LEB128 varint (7 bits/byte, MSB = continuation) → base64-encode the byte buffer. The
 * empty set encodes to the empty string (the caller then omits the tag).
 *
 * <p>Golden vector: {@code {100, 108, 128, 130}} → deltas {@code [100, 8, 20, 2]} → bytes {@code
 * [0x64, 0x08, 0x14, 0x02]} → base64 {@code "ZAgUAg=="}.
 */
final class ULeb128Encoder {

  private ULeb128Encoder() {}

  /**
   * ULEB128 delta-varint encodes the given serial ids into a bare base64 string.
   *
   * @param serialIds the serial ids to encode (deduped + sorted ascending internally)
   * @return the base64-encoded delta-varint bytes, or the empty string when {@code serialIds} is
   *     empty or null
   */
  static String encodeDeltaVarint(final Set<Integer> serialIds) {
    if (serialIds == null || serialIds.isEmpty()) {
      // Empty set encodes to the empty string; the caller omits the tag.
      return "";
    }
    final SortedSet<Integer> sorted =
        serialIds instanceof SortedSet ? (SortedSet<Integer>) serialIds : new TreeSet<>(serialIds);
    // Worst case: 5 bytes per 32-bit varint.
    final byte[] buffer = new byte[sorted.size() * 5];
    int length = 0;
    int previous = 0;
    for (final Integer id : sorted) {
      long delta =
          ((long) id) - previous; // long to stay safe; deltas are non-negative (sorted asc)
      previous = id;
      while (delta > 0x7FL) {
        buffer[length++] = (byte) ((delta & 0x7FL) | 0x80L);
        delta >>>= 7;
      }
      buffer[length++] = (byte) (delta & 0x7FL);
    }
    final byte[] out = new byte[length];
    System.arraycopy(buffer, 0, out, 0, length);
    return Base64.getEncoder().encodeToString(out);
  }

  /**
   * Lower-case hex SHA-256 of the given string. Used to hash subject targeting keys before they are
   * emitted (privacy: subject keys are never emitted in clear text).
   *
   * @param value the value to hash
   * @return the lower-case hex SHA-256 digest
   */
  static String hashTargetingKey(final String value) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(hash.length * 2);
      for (final byte b : hash) {
        final int v = b & 0xFF;
        if (v < 0x10) {
          hex.append('0');
        }
        hex.append(Integer.toHexString(v));
      }
      return hex.toString();
    } catch (final NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JLS to be present on every JVM; this is unreachable in practice.
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
