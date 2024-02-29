package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.ipc.Serializer;
import java.nio.ByteBuffer;

public abstract class TestIdentifierSerializer {

  public static void serialize(Serializer serializer, TestIdentifier testIdentifier) {
    serializer.write(testIdentifier.getSuite());
    serializer.write(testIdentifier.getName());
    serializer.write(testIdentifier.getParameters());
  }

  public static TestIdentifier deserialize(ByteBuffer buffer) {
    return new TestIdentifier(
        Serializer.readString(buffer),
        Serializer.readString(buffer),
        Serializer.readString(buffer),
        null);
  }
}
