package datadog.trace.api.iast;

import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge between instrumentations and {@link IastModule} that contains the business logic relative
 * to vulnerability detection. The class contains a list of {@code public static} methods that will
 * be injected into the bytecode via {@code invokestatic} instructions. It's important that all
 * methods are protected from exception leakage.
 */
public abstract class InstrumentationBridge {

  private static final Logger LOG = LoggerFactory.getLogger(InstrumentationBridge.class);

  private static IastModule MODULE;

  private InstrumentationBridge() {}

  public static void registerIastModule(final IastModule module) {
    MODULE = module;
  }

  /**
   * Executed when access to a cryptographic cipher is detected
   *
   * <p>{@link javax.crypto.Cipher#getInstance(String)}
   */
  public static void onCipherGetInstance(@Nonnull final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onCipherAlgorithm(algorithm);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onCipher threw.", t);
    }
  }

  /**
   * Executed when access to a message digest algorithm is detected
   *
   * <p>{@link java.security.MessageDigest#getInstance(String)}
   */
  public static void onMessageDigestGetInstance(@Nonnull final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onHashingAlgorithm(algorithm);
      }
    } catch (final Throwable t) {
      onUnexpectedException("Callback for onHash threw.", t);
    }
  }

  private static void onUnexpectedException(final String message, final Throwable error) {
    LOG.warn(message, error);
  }
}
