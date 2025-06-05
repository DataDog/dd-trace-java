package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;

public abstract class TestFQNSerializer {
  public static void serialize(Serializer serializer, TestFQN testFQN) {
    serializer.write(testFQN.getSuite());
    serializer.write(testFQN.getName());
  }

  public static TestFQN deserialize(ByteBuffer buffer) {
    String suiteName = Serializer.readString(buffer);
    return new TestFQN(
        // suite name repeats a lot; interning it to save memory
        suiteName != null ? suiteName.intern() : null, Serializer.readString(buffer));
  }
}
