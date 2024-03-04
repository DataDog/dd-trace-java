package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.config.JvmInfo;
import java.nio.ByteBuffer;
import java.util.Objects;

public class ModuleSettingsRequest implements Signal {

  private final String moduleName;
  private final JvmInfo jvmInfo;

  public ModuleSettingsRequest(String moduleName, JvmInfo jvmInfo) {
    this.moduleName = moduleName;
    this.jvmInfo = jvmInfo;
  }

  @Override
  public SignalType getType() {
    return SignalType.MODULE_SETTINGS_REQUEST;
  }

  public String getModuleName() {
    return moduleName;
  }

  public JvmInfo getJvmInfo() {
    return jvmInfo;
  }

  @Override
  public String toString() {
    return "ModuleSettingsRequest{" + "moduleName=" + moduleName + ", jvmInfo=" + jvmInfo + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ModuleSettingsRequest that = (ModuleSettingsRequest) o;
    return Objects.equals(moduleName, that.moduleName) && Objects.equals(jvmInfo, that.jvmInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(moduleName, jvmInfo);
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(moduleName);
    JvmInfo.serialize(s, jvmInfo);
    return s.flush();
  }

  public static ModuleSettingsRequest deserialize(ByteBuffer buffer) {
    String moduleName = Serializer.readString(buffer);
    JvmInfo jvmInfo = JvmInfo.deserialize(buffer);
    return new ModuleSettingsRequest(moduleName, jvmInfo);
  }
}
