package com.example.cryptobench.shared;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

public final class Jwt {
  private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DEC = Base64.getUrlDecoder();

  public static final String RS256_HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
  public static final String ES256_HEADER = "{\"alg\":\"ES256\",\"typ\":\"JWT\"}";
  public static final String CLAIMS =
      "{\"sub\":\"bench-user\",\"iss\":\"https://issuer.example\",\"aud\":\"crypto-bench\","
          + "\"iat\":1700000000,\"exp\":4102444800,\"scope\":\"read write\"}";

  private Jwt() {}

  public static String sign(String alg, PrivateKey key, String header, String claims)
      throws GeneralSecurityException {
    String signingInput =
        ENC.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + '.'
            + ENC.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    Signature s = Signature.getInstance(jcaName(alg));
    s.initSign(key);
    s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + '.' + ENC.encodeToString(s.sign());
  }

  public static String verify(String alg, PublicKey key, String token)
      throws GeneralSecurityException {
    int dot2 = token.lastIndexOf('.');
    int dot1 = token.indexOf('.');
    if (dot1 < 0 || dot2 <= dot1) {
      return null;
    }
    Signature s = Signature.getInstance(jcaName(alg));
    s.initVerify(key);
    s.update(token.substring(0, dot2).getBytes(StandardCharsets.US_ASCII));
    if (!s.verify(DEC.decode(token.substring(dot2 + 1)))) {
      return null;
    }
    return new String(DEC.decode(token.substring(dot1 + 1, dot2)), StandardCharsets.UTF_8);
  }

  static String jcaName(String alg) {
    switch (alg) {
      case "RS256":
        return "SHA256withRSA";
      case "ES256":
        return "SHA256withECDSAinP1363Format";
      default:
        throw new IllegalArgumentException(alg);
    }
  }
}
