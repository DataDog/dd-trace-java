package datadog.trace.api.iast;

import java.util.Iterator;
import java.util.ServiceLoader;

public abstract class InstrumentationBridge {

  static final IASTModule MODULE = initializeModule();

  private InstrumentationBridge() {}

  public static void onCipher(final String algorithm) {
    if (MODULE != null) {
      MODULE.onCipher(algorithm);
    }
  }

  public static void onHash(final String algorithm) {
    if (MODULE != null) {
      MODULE.onHash(algorithm);
    }
  }

  private static IASTModule initializeModule() {
    Iterator<IASTModule> loader =
        ServiceLoader.load(IASTModule.class, InstrumentationBridge.class.getClassLoader())
            .iterator();
    return loader.hasNext() ? loader.next() : null;
  }
}
