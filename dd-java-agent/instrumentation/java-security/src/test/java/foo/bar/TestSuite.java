package foo.bar;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestSuite {
  private static final Logger log = LoggerFactory.getLogger(TestSuite.class);

  public Cipher getCipherInstance(String algo)
      throws NoSuchPaddingException, NoSuchAlgorithmException {
    log.debug("Before Cipher.getInstance");
    Cipher c = Cipher.getInstance(algo);
    log.debug("after Cipher.getInstance");
    return c;
  }

  public MessageDigest getMessageDigestInstance(String algo) throws NoSuchAlgorithmException {
    log.debug("before MessageDigest.getInstance");
    MessageDigest md = MessageDigest.getInstance(algo);
    log.debug("after MessageDigest.getInstance");
    return md;
  }
}
