package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;

public class ErrorResponse implements SignalResponse {

  private final String message;

  public ErrorResponse(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }

  @Override
  public SignalType getType() {
    return SignalType.ERROR;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(message);
    return s.flush();
  }

  public static ErrorResponse deserialize(ByteBuffer buffer) {
    String message = Serializer.readString(buffer);
    return new ErrorResponse(message);
  }
}
