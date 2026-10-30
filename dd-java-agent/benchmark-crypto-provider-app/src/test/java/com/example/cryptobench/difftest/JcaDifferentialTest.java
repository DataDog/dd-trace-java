package com.example.cryptobench.difftest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.cryptobench.shared.Accp;
import com.example.cryptobench.shared.Fixtures;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.stream.Stream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JcaDifferentialTest {
  static Provider accp;

  static final int[] SIZES = {0, 1, 15, 16, 17, 55, 56, 63, 64, 65, 1000, 4096, 16 * 1024 + 3, 1 << 20};

  @BeforeAll
  static void loadAccp() {
    accp = Accp.provider();
  }

  static Stream<byte[]> inputs() {
    return java.util.Arrays.stream(SIZES).mapToObj(n -> Fixtures.bytes(n, n + 101));
  }

  static Provider jdk(String name) {
    Provider p = Security.getProvider(name);
    assertTrue(p != null && !Accp.NAME.equals(p.getName()), name);
    return p;
  }

  @ParameterizedTest
  @ValueSource(strings = {"SHA-256", "SHA-512", "SHA-384", "SHA-1"})
  void digestsAreIdentical(String alg) throws Exception {
    inputs()
        .forEach(
            in -> {
              try {
                byte[] a = MessageDigest.getInstance(alg, jdk("SUN")).digest(in);
                byte[] b = MessageDigest.getInstance(alg, accp).digest(in);
                assertArrayEquals(a, b, alg + " len=" + in.length);
                MessageDigest inc = MessageDigest.getInstance(alg, accp);
                for (int off = 0; off < in.length; off += 7) {
                  inc.update(in, off, Math.min(7, in.length - off));
                }
                assertArrayEquals(a, inc.digest(), alg + " incremental len=" + in.length);
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"HmacSHA256", "HmacSHA512", "HmacSHA384", "HmacSHA1"})
  void hmacsAreIdentical(String alg) throws Exception {
    for (int keyLen : new int[] {16, 32, 64, 65, 200}) {
      SecretKeySpec key = new SecretKeySpec(Fixtures.bytes(keyLen, keyLen), alg);
      inputs()
          .forEach(
              in -> {
                try {
                  Mac a = Mac.getInstance(alg, jdk("SunJCE"));
                  a.init(key);
                  Mac b = Mac.getInstance(alg, accp);
                  b.init(key);
                  assertArrayEquals(a.doFinal(in), b.doFinal(in), alg + " key=" + keyLen + " len=" + in.length);
                } catch (Exception e) {
                  throw new AssertionError(e);
                }
              });
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {128, 256})
  void aesGcmIsIdenticalAndCrossDecrypts(int keyBits) throws Exception {
    SecretKeySpec key = keyBits == 128 ? Fixtures.AES_128 : Fixtures.AES_256;
    GCMParameterSpec spec = new GCMParameterSpec(128, Fixtures.GCM_IV);
    for (byte[] in : (Iterable<byte[]>) inputs()::iterator) {
      byte[] jdkCt = gcm(Cipher.ENCRYPT_MODE, jdk("SunJCE"), key, spec, in);
      byte[] accpCt = gcm(Cipher.ENCRYPT_MODE, accp, key, spec, in);
      assertArrayEquals(jdkCt, accpCt, "ciphertext+tag len=" + in.length);
      assertArrayEquals(in, gcm(Cipher.DECRYPT_MODE, accp, key, spec, jdkCt));
      assertArrayEquals(in, gcm(Cipher.DECRYPT_MODE, jdk("SunJCE"), key, spec, accpCt));
    }
    byte[] ct = gcm(Cipher.ENCRYPT_MODE, accp, key, spec, Fixtures.bytes(100, 9));
    ct[ct.length - 1] ^= 1;
    assertThrows(AEADBadTagException.class, () -> gcm(Cipher.DECRYPT_MODE, jdk("SunJCE"), key, spec, ct));
    assertThrows(AEADBadTagException.class, () -> gcm(Cipher.DECRYPT_MODE, accp, key, spec, ct));
  }

  private static byte[] gcm(int mode, Provider p, SecretKeySpec key, GCMParameterSpec spec, byte[] in)
      throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding", p);
    c.init(mode, key, spec);
    c.updateAAD(Fixtures.AAD);
    return c.doFinal(in);
  }

  @ParameterizedTest
  @ValueSource(strings = {"AES/CBC/PKCS5Padding", "AES/CBC/NoPadding"})
  void aesCbcIsIdenticalAndCrossDecrypts(String transformation) throws Exception {
    IvParameterSpec iv = new IvParameterSpec(Fixtures.CBC_IV);
    for (SecretKeySpec key : new SecretKeySpec[] {Fixtures.AES_128, Fixtures.AES_256}) {
      for (byte[] in : (Iterable<byte[]>) inputs()::iterator) {
        if (transformation.endsWith("NoPadding") && in.length % 16 != 0) {
          continue;
        }
        byte[] a = cbc(Cipher.ENCRYPT_MODE, transformation, jdk("SunJCE"), key, iv, in);
        byte[] b = cbc(Cipher.ENCRYPT_MODE, transformation, accp, key, iv, in);
        assertArrayEquals(a, b, transformation + " len=" + in.length);
        assertArrayEquals(in, cbc(Cipher.DECRYPT_MODE, transformation, accp, key, iv, a));
        assertArrayEquals(in, cbc(Cipher.DECRYPT_MODE, transformation, jdk("SunJCE"), key, iv, b));
      }
    }
  }

  private static byte[] cbc(
      int mode, String transformation, Provider p, SecretKeySpec key, IvParameterSpec iv, byte[] in)
      throws Exception {
    Cipher c = Cipher.getInstance(transformation, p);
    c.init(mode, key, iv);
    return c.doFinal(in);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SHA256withECDSA", "SHA256withECDSAinP1363Format", "SHA384withECDSA"})
  void ecdsaP256CrossVerifiesBothWays(String alg) throws Exception {
    KeyPair kp = Fixtures.keyPair(Fixtures.EC_STORE);
    PublicKey accpPub = KeyFactory.getInstance("EC", accp).generatePublic(new X509EncodedKeySpec(kp.getPublic().getEncoded()));
    PrivateKey accpPriv =
        KeyFactory.getInstance("EC", accp).generatePrivate(new PKCS8EncodedKeySpec(kp.getPrivate().getEncoded()));
    Provider sunEc = jdk("SunEC");
    for (byte[] msg : (Iterable<byte[]>) inputs()::iterator) {
      byte[] jdkSig = sign(alg, sunEc, kp.getPrivate(), msg);
      byte[] accpSig = sign(alg, accp, accpPriv, msg);
      assertTrue(verify(alg, accp, accpPub, msg, jdkSig), "JDK-signed, ACCP-verified len=" + msg.length);
      assertTrue(verify(alg, sunEc, kp.getPublic(), msg, accpSig), "ACCP-signed, JDK-verified len=" + msg.length);
      assertTrue(verify(alg, accp, kp.getPublic(), msg, jdkSig));
      assertTrue(verify(alg, sunEc, accpPub, msg, accpSig));
      byte[] bad = msg.clone();
      if (bad.length > 0) {
        bad[0] ^= 1;
        assertFalse(verify(alg, accp, accpPub, bad, jdkSig));
        assertFalse(verify(alg, sunEc, kp.getPublic(), bad, accpSig));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"SHA256withRSA", "SHA512withRSA"})
  void rsa2048SignaturesAreIdenticalAndCrossVerify(String alg) throws Exception {
    KeyPair kp = Fixtures.keyPair(Fixtures.RSA_STORE);
    PublicKey accpPub =
        KeyFactory.getInstance("RSA", accp).generatePublic(new X509EncodedKeySpec(kp.getPublic().getEncoded()));
    PrivateKey accpPriv =
        KeyFactory.getInstance("RSA", accp).generatePrivate(new PKCS8EncodedKeySpec(kp.getPrivate().getEncoded()));
    Provider sunRsa = jdk("SunRsaSign");
    for (byte[] msg : (Iterable<byte[]>) inputs()::iterator) {
      byte[] jdkSig = sign(alg, sunRsa, kp.getPrivate(), msg);
      byte[] accpSig = sign(alg, accp, accpPriv, msg);
      assertArrayEquals(jdkSig, accpSig, alg + " len=" + msg.length);
      assertTrue(verify(alg, accp, accpPub, msg, jdkSig));
      assertTrue(verify(alg, sunRsa, kp.getPublic(), msg, accpSig));
      assertTrue(verify(alg, accp, kp.getPublic(), msg, jdkSig));
    }
    byte[] sig = sign(alg, accp, accpPriv, Fixtures.bytes(64, 1));
    sig[10] ^= 1;
    assertFalse(verify(alg, sunRsa, kp.getPublic(), Fixtures.bytes(64, 1), sig));
    assertFalse(verify(alg, accp, accpPub, Fixtures.bytes(64, 1), sig));
  }

  private static byte[] sign(String alg, Provider p, PrivateKey key, byte[] msg) throws Exception {
    Signature s = Signature.getInstance(alg, p);
    s.initSign(key);
    s.update(msg);
    return s.sign();
  }

  private static boolean verify(String alg, Provider p, PublicKey key, byte[] msg, byte[] sig)
      throws Exception {
    Signature s = Signature.getInstance(alg, p);
    s.initVerify(key);
    s.update(msg);
    try {
      return s.verify(sig);
    } catch (java.security.SignatureException e) {
      return false;
    }
  }

  @Test
  void ecdhP256AgreementIsIdentical() throws Exception {
    Provider sunEc = jdk("SunEC");
    for (int i = 0; i < 20; i++) {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC", sunEc);
      g.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair a = g.generateKeyPair();
      KeyPair b = g.generateKeyPair();
      byte[] jdkAb = agree(sunEc, a.getPrivate(), b.getPublic());
      byte[] accpAb = agree(accp, a.getPrivate(), b.getPublic());
      byte[] accpBa = agree(accp, b.getPrivate(), a.getPublic());
      byte[] jdkBa = agree(sunEc, b.getPrivate(), a.getPublic());
      assertArrayEquals(jdkAb, accpAb);
      assertArrayEquals(jdkAb, accpBa);
      assertArrayEquals(jdkAb, jdkBa);
      assertEquals(32, jdkAb.length);
    }
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC", accp);
    g.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair a = g.generateKeyPair();
    KeyPair b = g.generateKeyPair();
    assertArrayEquals(agree(sunEc, a.getPrivate(), b.getPublic()), agree(accp, b.getPrivate(), a.getPublic()));
  }

  private static byte[] agree(Provider p, PrivateKey mine, PublicKey theirs) throws Exception {
    KeyAgreement ka = KeyAgreement.getInstance("ECDH", p);
    ka.init(mine);
    ka.doPhase(theirs, true);
    return ka.generateSecret();
  }
}
