package datadog.trace.bootstrap.instrumentation.api;

public enum ScopeSource {
  INSTRUMENTATION((byte) 0),
  MANUAL((byte) 1);

  private final byte id;

  ScopeSource(byte id) {
    this.id = id;
  }

  public byte id() {
    return id;
  }
}
