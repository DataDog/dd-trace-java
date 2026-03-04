package datadog.trace.bootstrap.instrumentation.ffm;

import datadog.trace.api.Pair;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeLibraryHelper {
  // this map is unlimited. However, the number of entries depends on the configured methods we want
  // to trace.
  private static final ConcurrentHashMap<Long, Pair<String, String>> SYMBOLS_MAP =
      new ConcurrentHashMap<>();

  private NativeLibraryHelper() {}

  public static void onSymbolLookup(
      final String libraryName, final String symbol, final long address) {
    if (libraryName != null && !libraryName.isEmpty()) {
      if (FFMNativeMethodDecorator.isMethodTraced(libraryName, symbol)) {
        SYMBOLS_MAP.put(address, Pair.of(libraryName, symbol));
      }
    }
  }

  public static Pair<String, String> reverseResolveLibraryAndSymbol(long address) {
    return SYMBOLS_MAP.get(address);
  }
}
