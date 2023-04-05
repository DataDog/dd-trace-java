package datadog.trace.bootstrap.instrumentation.usm;

public interface UsmExtractor {
  void send(UsmMessage message);

  abstract class Supplier {
    private static volatile UsmExtractor SUPPLIER;

    public static void send(UsmMessage message) {
      SUPPLIER.send(message);
    }

    public static synchronized void registerIfAbsent(UsmExtractor supplier) {
      if (SUPPLIER == null) {
        SUPPLIER = supplier;
      }
    }
  }
}
