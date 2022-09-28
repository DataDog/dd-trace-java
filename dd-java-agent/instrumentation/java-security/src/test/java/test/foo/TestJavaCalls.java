package test.foo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

public class TestJavaCalls {

  public Cipher getCipherInstance(String algo)
      throws NoSuchPaddingException, NoSuchAlgorithmException {
    return Cipher.getInstance(algo);
  }

  public MessageDigest getMessageDigestInstance(String algo) throws NoSuchAlgorithmException {
    return MessageDigest.getInstance(algo);
  }
}
