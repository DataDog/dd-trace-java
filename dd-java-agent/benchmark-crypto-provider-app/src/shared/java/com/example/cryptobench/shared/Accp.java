package com.example.cryptobench.shared;

import java.security.Provider;
import java.security.Security;

/** Reflective access to ACCP so shared code compiles and runs without it on the class path. */
public final class Accp {
  public static final String CLASS_NAME =
      "com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider";
  public static final String NAME = "AmazonCorrettoCryptoProvider";

  private Accp() {}

  /** The ACCP singleton after its health check, or an exception explaining why it is unusable. */
  public static Provider provider() {
    try {
      Class<?> c = Class.forName(CLASS_NAME);
      Provider p = (Provider) c.getField("INSTANCE").get(null);
      Object loadingError = c.getMethod("getLoadingError").invoke(p);
      if (loadingError != null) {
        throw new IllegalStateException("ACCP native library failed to load", (Throwable) loadingError);
      }
      c.getMethod("assertHealthy").invoke(p);
      return p;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("ACCP is not usable here", e);
    }
  }

  public static Provider install() {
    Provider p = provider();
    Security.insertProviderAt(p, 1);
    if (Security.getProviders()[0] != p) {
      throw new IllegalStateException("ACCP did not end up at position 1");
    }
    return p;
  }
}
