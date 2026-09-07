package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  private final String value;
  private final int inheritedPosition;
  private final int originalMemberContributionSize;

  private OtelTraceState(String value, int inheritedPosition, int originalMemberContributionSize) {
    this.value = value;
    this.inheritedPosition = inheritedPosition;
    this.originalMemberContributionSize = originalMemberContributionSize;
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

  int getInheritedPosition() {
    return inheritedPosition;
  }

  int getOriginalMemberContributionSize() {
    return originalMemberContributionSize;
  }
}
