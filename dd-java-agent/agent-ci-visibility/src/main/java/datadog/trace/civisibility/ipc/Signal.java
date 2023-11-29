package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;

public interface Signal {
  SignalType getType();

  ByteBuffer serialize();
}
