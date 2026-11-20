package datadog.trace.api;

/**
 * Bridge class to allow dd-trace-core tests to read a {@link SizingHint}'s package-private size.
 */
public final class SizingHintTestBridge {
  private SizingHintTestBridge() {}

  public static int sizeOf(SizingHint hint) {
    return hint.size;
  }
}
