package datadog.trace.civisibility.ipc;

public enum SignalType {
  MODULE_EXECUTION_RESULT((byte) 0),
  ACK((byte) 1),
  ERROR((byte) 2);

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
