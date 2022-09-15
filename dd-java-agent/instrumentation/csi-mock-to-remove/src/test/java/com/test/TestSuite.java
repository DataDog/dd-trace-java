package com.test;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class TestSuite {

  public static MessageDigest messageDigestGetInstance(final String algorithm)
      throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    if (digest == null) {
      throw new RuntimeException("No digest found");
    }
    return digest;
  }

  public static URL urlInit(final String url) throws MalformedURLException {
    return new URL(url);
  }

  public static String stringConcat(final String self, final String param) {
    return self.concat(param);
  }
}
