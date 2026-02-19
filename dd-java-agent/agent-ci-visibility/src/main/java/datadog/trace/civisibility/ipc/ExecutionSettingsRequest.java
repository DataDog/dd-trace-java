package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.config.JvmInfo;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

public class ExecutionSettingsRequest implements Signal {

  private final String moduleName;
  private final JvmInfo jvmInfo;

  public ExecutionSettingsRequest(String moduleName, JvmInfo jvmInfo) {
    this.moduleName = moduleName;
    this.jvmInfo = jvmInfo;
  }

  @Override
  public SignalType getType() {
    return SignalType.EXECUTION_SETTINGS_REQUEST;
  }

  public String getModuleName() {
    return moduleName;
  }

  public JvmInfo getJvmInfo() {
    return jvmInfo;
  }

  @Override
  public String toString() {
    return "ExecutionSettingsRequest{" + "moduleName=" + moduleName + ", jvmInfo=" + jvmInfo + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ExecutionSettingsRequest that = (ExecutionSettingsRequest) o;
    return Objects.equals(moduleName, that.moduleName) && Objects.equals(jvmInfo, that.jvmInfo);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(moduleName, jvmInfo);
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(moduleName);
    JvmInfo.serialize(s, jvmInfo);
    return s.flush();
  }

  public static ExecutionSettingsRequest deserialize(ByteBuffer buffer) {
    String moduleName = Serializer.readString(buffer);
    JvmInfo jvmInfo = JvmInfo.deserialize(buffer);
    return new ExecutionSettingsRequest(moduleName, jvmInfo);
  }
}
