package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  private final String value;
  private final int originalPosition;
  private final int originalSize;

  private OtelTraceState(String value, int inheritedPosition, int originalMemberContributionSize) {
    this.value = value;
    this.originalPosition = inheritedPosition;
    this.originalSize = originalMemberContributionSize;
  }

  static OtelTraceState parse(
      String raw, int inheritedPosition, int originalMemberContributionSize) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return new OtelTraceState(raw, inheritedPosition, originalMemberContributionSize);
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
