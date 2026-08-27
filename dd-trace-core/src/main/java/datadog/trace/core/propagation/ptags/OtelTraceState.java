package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  private final String value;
  private final int inheritedPosition;

  private OtelTraceState(String value, int inheritedPosition) {
    this.value = value;
    this.inheritedPosition = inheritedPosition;
  }

  static OtelTraceState parse(String raw, int inheritedPosition) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return new OtelTraceState(raw, inheritedPosition);
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
}
