package com.example.cryptobench.shared;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import javax.crypto.spec.SecretKeySpec;

/** Fixed keys and payloads, so outputs can be compared byte for byte across providers. */
public final class Fixtures {
  public static final char[] STORE_PASSWORD = "changeit".toCharArray();
  public static final String RSA_STORE = "keys/bench-rsa.p12";
  public static final String EC_STORE = "keys/bench-ec.p12";

  public static final SecretKeySpec AES_128 = new SecretKeySpec(bytes(16, 0x11), "AES");
  public static final SecretKeySpec AES_256 = new SecretKeySpec(bytes(32, 0x22), "AES");
  public static final SecretKeySpec HMAC_KEY = new SecretKeySpec(bytes(32, 0x33), "HmacSHA256");
  public static final byte[] GCM_IV = bytes(12, 0x44);
  public static final byte[] CBC_IV = bytes(16, 0x55);
  public static final byte[] AAD = bytes(20, 0x66);

  private Fixtures() {}

  /** Deterministic pseudo-random bytes (xorshift), so the same seed gives the same payload. */
  public static byte[] bytes(int length, long seed) {
    byte[] out = new byte[length];
    long x = seed * 0x9E3779B97F4A7C15L + 1;
    for (int i = 0; i < length; i++) {
      x ^= x << 13;
      x ^= x >>> 7;
      x ^= x << 17;
      out[i] = (byte) x;
    }
    return out;
  }

  public static KeyStore keyStore(String resource) {
    try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + resource);
      }
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(in, STORE_PASSWORD);
      return ks;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException("cannot load " + resource, e);
    }
  }

  public static KeyPair keyPair(String resource) {
    try {
      KeyStore ks = keyStore(resource);
      PrivateKey privateKey = (PrivateKey) ks.getKey("bench", STORE_PASSWORD);
      Certificate cert = ks.getCertificate("bench");
      return new KeyPair(cert.getPublicKey(), privateKey);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte x : b) {
      sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
    }
    return sb.toString();
  }
}
