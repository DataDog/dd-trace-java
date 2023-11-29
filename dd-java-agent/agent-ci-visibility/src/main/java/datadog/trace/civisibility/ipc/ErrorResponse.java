package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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
    byte[] payload = message.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.wrap(payload);
  }

  public static ErrorResponse deserialize(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    String message = new String(bytes, StandardCharsets.UTF_8);
    return new ErrorResponse(message);
  }
}
