package datadog.trace.civisibility.transform;

import datadog.trace.civisibility.ipc.Serializer;
import datadog.trace.civisibility.ipc.Signal;
import datadog.trace.civisibility.ipc.SignalType;
import java.nio.ByteBuffer;

public class ClassMatchingRequest implements Signal {

  public static final ClassMatchingRequest INSTANCE = new ClassMatchingRequest();

  private ClassMatchingRequest() {}

  @Override
  public SignalType getType() {
    return SignalType.CLASS_MATCHING_REQUEST;
  }

  @Override
  public ByteBuffer serialize() {
    return new Serializer().flush();
  }

  public static ClassMatchingRequest deserialize(ByteBuffer buffer) {
    return INSTANCE;
  }
}
