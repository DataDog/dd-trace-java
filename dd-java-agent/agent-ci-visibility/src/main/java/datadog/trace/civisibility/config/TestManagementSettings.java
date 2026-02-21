package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import datadog.trace.util.HashingUtils;

public class TestManagementSettings {

  public static final TestManagementSettings DEFAULT = new TestManagementSettings(false, -1);

  private final boolean enabled;
  private final int attemptToFixRetries;

  public TestManagementSettings(boolean enabled, int attemptToFixRetries) {
    this.enabled = enabled;
    this.attemptToFixRetries = attemptToFixRetries;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getAttemptToFixRetries() {
    return attemptToFixRetries;
  }

  public List<ExecutionsByDuration> getAttemptToFixExecutions() {
    return Collections.singletonList(new ExecutionsByDuration(Long.MAX_VALUE, attemptToFixRetries));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    TestManagementSettings that = (TestManagementSettings) o;
    return enabled == that.enabled && attemptToFixRetries == that.attemptToFixRetries;
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(enabled, attemptToFixRetries);
  }

  public static final class Serializer {
    public static void serialize(
        datadog.trace.civisibility.ipc.serialization.Serializer serializer,
        TestManagementSettings settings) {
      if (!settings.enabled) {
        serializer.write((byte) 0);
        return;
      }
      serializer.write((byte) 1);
      serializer.write(settings.attemptToFixRetries);
    }

    public static TestManagementSettings deserialize(ByteBuffer buf) {
      boolean enabled = datadog.trace.civisibility.ipc.serialization.Serializer.readByte(buf) != 0;
      if (!enabled) {
        return TestManagementSettings.DEFAULT;
      }

      int attemptToFixRetries =
          datadog.trace.civisibility.ipc.serialization.Serializer.readInt(buf);
      return new TestManagementSettings(enabled, attemptToFixRetries);
    }
  }

  public static final class JsonAdapter {
    public static final JsonAdapter INSTANCE = new JsonAdapter();

    @FromJson
    public TestManagementSettings fromJson(Map<String, Object> json) {
      if (json == null) {
        return TestManagementSettings.DEFAULT;
      }

      Boolean enabled = (Boolean) json.get("enabled");
      Double attemptToFixRetries = (Double) json.get("attempt_to_fix_retries");

      return new TestManagementSettings(
          enabled != null ? enabled : false,
          attemptToFixRetries != null ? attemptToFixRetries.intValue() : 20);
    }
  }
}
