package datadog.trace.civisibility.ipc;

public enum SignalType {
  // requests
  MODULE_EXECUTION_RESULT((byte) 0),
  REPO_INDEX_REQUEST((byte) 1),
  // responses
  ACK((byte) 2),
  ERROR((byte) 3),
  REPO_INDEX_RESPONSE((byte) 4);

  private static final SignalType[] VALUES = SignalType.values();

  private final byte code;

  SignalType(byte code) {
    this.code = code;
  }

  public byte getCode() {
    return code;
  }

  public static SignalType fromCode(byte code) {
    for (SignalType value : VALUES) {
      if (value.code == code) {
        return value;
      }
    }
    return null;
  }
}
