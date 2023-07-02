package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;

public interface Signal {

  ByteBuffer serialize();
}
