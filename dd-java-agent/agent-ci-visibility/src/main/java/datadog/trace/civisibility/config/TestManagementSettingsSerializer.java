package datadog.trace.civisibility.config;

import datadog.trace.civisibility.ipc.serialization.Serializer;

import java.nio.ByteBuffer;

public class TestManagementSettingsSerializer {
  public static void serialize(Serializer serializer, TestManagementSettings settings) {
    if (!settings.isEnabled()) {
      serializer.write((byte) 0);
      return;
    }
    serializer.write((byte) 1);
    serializer.write(settings.getAttemptToFixRetries());
  }

  public static TestManagementSettings deserialize(ByteBuffer buf) {
    boolean enabled = Serializer.readByte(buf) != 0;
    if (!enabled) {
      return TestManagementSettings.DEFAULT;
    }

    int attemptToFixRetries = Serializer.readInt(buf);
    return new TestManagementSettings(enabled, attemptToFixRetries);
  }
}
