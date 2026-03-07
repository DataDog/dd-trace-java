package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.config.ExecutionSettings;
import java.nio.ByteBuffer;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

public class ExecutionSettingsResponse implements SignalResponse {

  private final ExecutionSettings settings;

  public ExecutionSettingsResponse(ExecutionSettings settings) {
    this.settings = settings;
  }

  @Override
  public SignalType getType() {
    return SignalType.MODULE_SETTINGS_RESPONSE;
  }

  public ExecutionSettings getSettings() {
    return settings;
  }

  @Override
  public String toString() {
    return "ExecutionSettingsResponse{" + "settings=" + settings + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ExecutionSettingsResponse that = (ExecutionSettingsResponse) o;
    return Objects.equals(settings, that.settings);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(settings);
  }

  @Override
  public ByteBuffer serialize() {
    return ExecutionSettings.Serializer.serialize(settings);
  }

  public static ExecutionSettingsResponse deserialize(ByteBuffer buffer) {
    ExecutionSettings settings = ExecutionSettings.Serializer.deserialize(buffer);
    return new ExecutionSettingsResponse(settings);
  }
}
