package com.example.cryptobench;

import com.example.cryptobench.shared.Fixtures;
import com.example.cryptobench.shared.Jwt;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CryptoController {
  static final int AES_PAYLOAD_BYTES = 4096;
  private static final byte[] AES_PAYLOAD = Fixtures.bytes(AES_PAYLOAD_BYTES, 7);
  private static final String NOCRYPTO_BODY;

  static {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 32; i++) {
      sb.append("field").append(i).append('=').append(i * 31).append(';');
    }
    NOCRYPTO_BODY = sb.toString();
  }

  private final AtomicLong counter = new AtomicLong();
  private final String token;
  private final String tamperedToken;
  final PublicKey jwtKey;

  public CryptoController() throws GeneralSecurityException {
    KeyPair rsa = Fixtures.keyPair(Fixtures.RSA_STORE);
    jwtKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(rsa.getPublic().getEncoded()));
    token = Jwt.sign("RS256", rsa.getPrivate(), Jwt.RS256_HEADER, Jwt.CLAIMS);
    char last = token.charAt(token.length() - 2);
    tamperedToken =
        token.substring(0, token.length() - 2) + (last == 'A' ? 'B' : 'A') + token.charAt(token.length() - 1);
  }

  @GetMapping("/nocrypto")
  public String noCrypto() {
    return NOCRYPTO_BODY;
  }

  @GetMapping("/jwt")
  public String jwt(@RequestParam(name = "tamper", defaultValue = "false") boolean tamper)
      throws GeneralSecurityException {
    String claims = Jwt.verify("RS256", jwtKey, tamper ? tamperedToken : token);
    return claims == null ? "invalid" : "valid " + claims;
  }

  @GetMapping("/aesgcm")
  public String aesGcm(@RequestParam(name = "n", defaultValue = "-1") long n)
      throws GeneralSecurityException {
    long ivCounter = n >= 0 ? n : counter.incrementAndGet();
    byte[] iv = ByteBuffer.allocate(12).putInt(0x62656e63).putLong(ivCounter).array();
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, Fixtures.AES_256, new GCMParameterSpec(128, iv));
    c.updateAAD(Fixtures.AAD);
    byte[] out = c.doFinal(AES_PAYLOAD);
    byte[] tag = new byte[16];
    System.arraycopy(out, out.length - 16, tag, 0, 16);
    return out.length + " " + Fixtures.hex(tag);
  }

  @GetMapping("/tls")
  public String tls() {
    return "ok";
  }
}
