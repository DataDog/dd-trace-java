package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;

public abstract class TestIdentifierSerializer {

  public static void serialize(Serializer serializer, TestIdentifier testIdentifier) {
    TestFQNSerializer.serialize(serializer, testIdentifier.toFQN());
    serializer.write(testIdentifier.getParameters());
  }

  public static TestIdentifier deserialize(ByteBuffer buffer) {
    return new TestIdentifier(TestFQNSerializer.deserialize(buffer), Serializer.readString(buffer));
  }
}
