package datadog.trace.api.iast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class InstrumentationBridgeShortCircuit {

  private static final Logger LOG =
      LoggerFactory.getLogger(InstrumentationBridgeShortCircuit.class);

  static IASTModule MODULE;

  private InstrumentationBridgeShortCircuit() {}

  public static void onCipherGetInstance(final String algorithm) {}

  public static void onMessageDigestGetInstance(final String algorithm) {}
}
