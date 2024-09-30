package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.civisibility.ipc.Serializer;
import java.nio.ByteBuffer;

public abstract class TestMetadataSerializer {

  public static void serialize(Serializer serializer, TestMetadata testMetadata) {
    serializer.write(testMetadata.isMissingLineCodeCoverage());
  }

  public static TestMetadata deserialize(ByteBuffer buffer) {
    return new TestMetadata(Serializer.readBoolean(buffer));
  }
}
