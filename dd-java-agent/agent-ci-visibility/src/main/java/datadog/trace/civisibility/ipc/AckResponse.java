package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;

public class AckResponse implements SignalResponse {

  public static final SignalResponse INSTANCE = new AckResponse();

  @Override
  public SignalType getType() {
    return SignalType.ACK;
  }

  @Override
  public ByteBuffer serialize() {
    return ByteBuffer.allocate(0);
  }
}
