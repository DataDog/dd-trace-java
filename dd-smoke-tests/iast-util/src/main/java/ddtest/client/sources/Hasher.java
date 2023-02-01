package ddtest.client.sources;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hasher {
  public MessageDigest sha1() {
    try {
      return MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  public MessageDigest md4() {
    try {
      return MessageDigest.getInstance("MD4");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  public MessageDigest md5() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }
}
