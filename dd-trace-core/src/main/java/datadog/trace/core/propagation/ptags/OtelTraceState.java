package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  private final String value;
  private final int originalPosition;
  private final int originalSize;

  private OtelTraceState(String value, int originalPosition, int originalSize) {
    this.value = value;
    this.originalPosition = originalPosition;
    this.originalSize = originalSize;
  }

  static OtelTraceState parse(String raw, int originalPosition, int originalSize) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return new OtelTraceState(raw, originalPosition, originalSize);
  }

  String getValue() {
    return value;
  }

  int length() {
    return value.length();
  }

  int getOriginalPosition() {
    return originalPosition;
  }

  int getOriginalSize() {
    return originalSize;
  }
}
