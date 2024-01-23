package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.Buffer;

public interface Extractor {
  void send(Buffer message);

  abstract class Supplier {
    private static volatile Extractor SUPPLIER;

    public static void send(Buffer message) {
      SUPPLIER.send(message);
    }

    public static synchronized void registerIfAbsent(Extractor supplier) {
      if (SUPPLIER == null) {
        SUPPLIER = supplier;
      }
    }
  }
}
