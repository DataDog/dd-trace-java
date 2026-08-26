package com.datadog.featureflag;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates the non-secret identifier required for authenticated Feature Flagging requests. */
final class ApiKeyFingerprint {
  private static final char[] BASE62 =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
  private static final BigInteger RADIX = BigInteger.valueOf(BASE62.length);
  private static final int SHA_256_BASE62_LENGTH = 43;

  private ApiKeyFingerprint() {}

  static String create(final String apiKey) {
    final byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }

    BigInteger value = new BigInteger(1, digest);
    final StringBuilder encoded = new StringBuilder(SHA_256_BASE62_LENGTH);
    do {
      final BigInteger[] division = value.divideAndRemainder(RADIX);
      encoded.append(BASE62[division[1].intValue()]);
      value = division[0];
    } while (value.signum() != 0);
    encoded.reverse();
    while (encoded.length() < SHA_256_BASE62_LENGTH) {
      encoded.insert(0, '0');
    }
    return "rijn_" + encoded;
  }
}
