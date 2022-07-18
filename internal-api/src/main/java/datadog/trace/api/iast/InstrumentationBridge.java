package datadog.trace.api.iast;

import datadog.trace.api.function.Supplier;

public abstract class InstrumentationBridge {

  static Supplier<IASTModule> MODULE = initializeModule();

  private InstrumentationBridge() {}

  public static void onCipher(final String algorithm) {
    MODULE.get().onCipher(algorithm);
  }

  public static void onHash(final String algorithm) {
    MODULE.get().onHash(algorithm);
  }

  private static Supplier<IASTModule> initializeModule() {
    return new Supplier<IASTModule>() {
      @Override
      public IASTModule get() {
        // TODO fetch module from current context
        throw new UnsupportedOperationException("not yet implemented");
      }
    };
  }
}
