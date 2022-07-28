package datadog.trace.api.iast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge between instrumentations and {@link IASTModule} that contains the business logic relative
 * to vulnerability detection. The class contains a list of {@code public static} methods that will
 * be injected into the bytecode via {@code invokestatic} instructions. It's important that all
 * methods are protected from exception leakage.
 */
public abstract class InstrumentationBridge {

  private static final Logger LOG = LoggerFactory.getLogger(InstrumentationBridge.class);

  // TODO (malvarez) inject current instance of the module via SPI or other compatible approach
  static IASTModule MODULE;

  private InstrumentationBridge() {}

  /**
   * Executed when access to a cryptographic cipher is detected
   *
   * <p>{@link javax.crypto.Cipher#getInstance(String)}
   */
  public static void onCipherGetInstance(final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onCipherAlgorithm(algorithm);
      }
    } catch (Throwable t) {
      LOG.warn("Callback for onCipher threw.", t);
    }
  }

  /**
   * Executed when access to a message digest algorithm is detected
   *
   * <p>{@link java.security.MessageDigest#getInstance(String)}
   */
  public static void onMessageDigestGetInstance(final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onHashingAlgorithm(algorithm);
      }
    } catch (Throwable t) {
      LOG.warn("Callback for onHash threw.", t);
    }
  }
}
