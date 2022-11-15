package ddtest.client.sources;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hasher {
  public MessageDigest sha1() throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA1");
  }

  public MessageDigest md5() throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("MD5");
  }
}
