package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.config.JvmInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
    byte[] modulePathBytes =
        moduleName != null ? moduleName.getBytes(StandardCharsets.UTF_8) : null;

    String jvmName = jvmInfo.getName();
    byte[] jvmNameBytes = jvmName != null ? jvmName.getBytes(StandardCharsets.UTF_8) : null;

    String jvmVersion = jvmInfo.getVersion();
    byte[] jvmVersionBytes =
        jvmVersion != null ? jvmVersion.getBytes(StandardCharsets.UTF_8) : null;

    String jvmVendor = jvmInfo.getVendor();
    byte[] jvmVendorBytes = jvmVendor != null ? jvmVendor.getBytes(StandardCharsets.UTF_8) : null;

    ByteBuffer buffer =
        ByteBuffer.allocate(
            serializeStringLength(modulePathBytes)
                + serializeStringLength(jvmNameBytes)
                + serializeStringLength(jvmVersionBytes)
                + serializeStringLength(jvmVendorBytes));

    serializeString(buffer, modulePathBytes);
    serializeString(buffer, jvmNameBytes);
    serializeString(buffer, jvmVersionBytes);
    serializeString(buffer, jvmVendorBytes);

    buffer.flip();
    return buffer;
  }

  private static int serializeStringLength(byte[] stringBytes) {
    return Integer.BYTES + (stringBytes != null ? stringBytes.length : 0);
  }

  private static void serializeString(ByteBuffer buffer, byte[] stringBytes) {
    if (stringBytes != null) {
      buffer.putInt(stringBytes.length);
      buffer.put(stringBytes);
    } else {
      buffer.putInt(-1);
    }
  }

  public static ModuleSettingsRequest deserialize(ByteBuffer buffer) {
    String moduleName = deserializeString(buffer);
    String jvmName = deserializeString(buffer);
    String jvmVersion = deserializeString(buffer);
    String jvmVendor = deserializeString(buffer);
    JvmInfo jvmInfo = new JvmInfo(jvmName, jvmVersion, jvmVendor);
    return new ModuleSettingsRequest(moduleName, jvmInfo);
  }

  private static String deserializeString(ByteBuffer buffer) {
    int jvmNameBytesLength = buffer.getInt();
    if (jvmNameBytesLength >= 0) {
      byte[] jvmNameBytes = new byte[jvmNameBytesLength];
      buffer.get(jvmNameBytes);
      return new String(jvmNameBytes, StandardCharsets.UTF_8);
    } else {
      return null;
    }
  }
}
