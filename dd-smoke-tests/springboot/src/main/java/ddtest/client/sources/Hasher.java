package ddtest.client.sources;

import datadog.smoketest.TestCallee;
import java.security.NoSuchAlgorithmException;

public class Hasher {
  public void executeHash() throws NoSuchAlgorithmException {
    // MessageDigest.getInstance("MD5");
    TestCallee.staticCall("MD5");
  }
}
