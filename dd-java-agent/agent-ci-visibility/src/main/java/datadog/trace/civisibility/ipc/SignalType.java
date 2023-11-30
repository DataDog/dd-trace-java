package datadog.trace.civisibility.ipc;

public enum SignalType {
  ACK((byte) 0),
  ERROR((byte) 1),
  MODULE_EXECUTION_RESULT((byte) 2),
  REPO_INDEX_REQUEST((byte) 3),
  REPO_INDEX_RESPONSE((byte) 4),
  SKIPPABLE_TESTS_REQUEST((byte) 5),
  SKIPPABLE_TESTS_RESPONSE((byte) 6);

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
