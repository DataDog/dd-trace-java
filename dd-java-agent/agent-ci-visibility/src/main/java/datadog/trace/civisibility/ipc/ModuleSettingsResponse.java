package datadog.trace.civisibility.ipc;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.civisibility.config.ModuleExecutionSettingsSerializer;
import java.nio.ByteBuffer;
import java.util.Objects;

public class ModuleSettingsResponse implements SignalResponse {

  private final ModuleExecutionSettings settings;

  public ModuleSettingsResponse(ModuleExecutionSettings settings) {
    this.settings = settings;
  }

  @Override
  public SignalType getType() {
    return SignalType.MODULE_SETTINGS_RESPONSE;
  }

  public ModuleExecutionSettings getSettings() {
    return settings;
  }

  @Override
  public String toString() {
    return "ModuleExecutionSettingsResponse{" + "settings=" + settings + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ModuleSettingsResponse that = (ModuleSettingsResponse) o;
    return Objects.equals(settings, that.settings);
  }

  @Override
  public int hashCode() {
    return Objects.hash(settings);
  }

  @Override
  public ByteBuffer serialize() {
    return ModuleExecutionSettingsSerializer.serialize(settings);
  }

  public static ModuleSettingsResponse deserialize(ByteBuffer buffer) {
    ModuleExecutionSettings settings = ModuleExecutionSettingsSerializer.deserialize(buffer);
    return new ModuleSettingsResponse(settings);
  }
}
