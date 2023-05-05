package datadog.smoketest.springboot.jwt;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class RSAKeyService {

  public static RSAPrivateKey readPrivateKey(byte[] privateKey) throws Exception {
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKey);
    return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
  }

  public static RSAPublicKey readPublicKey(byte[] publicKey) throws Exception {
    X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKey);
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
  }
}
