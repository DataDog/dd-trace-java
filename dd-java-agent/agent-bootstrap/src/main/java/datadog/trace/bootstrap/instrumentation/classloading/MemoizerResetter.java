package datadog.trace.bootstrap.instrumentation.classloading;

@FunctionalInterface
public interface MemoizerResetter {
  void reset();

  abstract class Supplier {
    private static volatile MemoizerResetter SUPPLIER;

    public static void reset() {
      if (SUPPLIER != null) {
        SUPPLIER.reset();
      }
    }

    public static synchronized void registerIfAbsent(MemoizerResetter supplier) {
      if (SUPPLIER == null) {
        SUPPLIER = supplier;
      }
    }
  }
}
