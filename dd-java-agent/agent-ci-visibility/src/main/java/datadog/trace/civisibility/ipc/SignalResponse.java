package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;

public interface SignalResponse {
  SignalType getType();

  ByteBuffer serialize();
}
