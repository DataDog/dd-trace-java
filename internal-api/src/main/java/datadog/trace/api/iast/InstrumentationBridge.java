package datadog.trace.api.iast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class InstrumentationBridge {

  private static final Logger LOG = LoggerFactory.getLogger(InstrumentationBridge.class);

  static IASTModule MODULE;

  private InstrumentationBridge() {}

  public static void onCipher(final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onCipher(algorithm);
      }
    } catch (Throwable t) {
      LOG.warn("Callback for onCipher threw.", t);
    }
  }

  public static void onHash(final String algorithm) {
    try {
      if (MODULE != null) {
        MODULE.onHash(algorithm);
      }
    } catch (Throwable t) {
      LOG.warn("Callback for onHash threw.", t);
    }
  }
}
