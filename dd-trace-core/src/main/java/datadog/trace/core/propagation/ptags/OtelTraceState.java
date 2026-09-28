package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  private final CharSequence value;
  private final int originalPosition;
  private final int originalSize;

  private OtelTraceState(CharSequence value, int originalPosition, int originalSize) {
    this.value = value;
    this.originalPosition = originalPosition;
    this.originalSize = originalSize;
  }

  static OtelTraceState parse(CharSequence raw, int originalPosition, int originalSize) {
    if (raw == null || raw.length() == 0) {
      return null;
    }
    return new OtelTraceState(raw, originalPosition, originalSize);
  }

  CharSequence getValue() {
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
