package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

public class JvmInfo {

  public static final JvmInfo CURRENT_JVM;

  static {
    Config config = Config.get();
    CiVisibilityWellKnownTags wellKnownTags = config.getCiVisibilityWellKnownTags();
    CURRENT_JVM =
        new JvmInfo(
            wellKnownTags.getRuntimeName().toString(),
            wellKnownTags.getRuntimeVersion().toString(),
            wellKnownTags.getRuntimeVendor().toString());
  }

  private final String name;
  private final String version;
  private final String vendor;

  public JvmInfo(String name, String version, String vendor) {
    this.name = name;
    this.version = version;
    this.vendor = vendor;
  }

  public String getName() {
    return name;
  }

  public String getVersion() {
    return version;
  }

  public String getVendor() {
    return vendor;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    JvmInfo jvmInfo = (JvmInfo) o;
    return Objects.equals(name, jvmInfo.name)
        && Objects.equals(version, jvmInfo.version)
        && Objects.equals(vendor, jvmInfo.vendor);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(name, version, vendor);
  }

  @Override
  public String toString() {
    return "JvmInfo{"
        + "name='"
        + name
        + '\''
        + ", version='"
        + version
        + '\''
        + ", vendor='"
        + vendor
        + '\''
        + '}';
  }

  public static void serialize(Serializer serializer, JvmInfo jvmInfo) {
    serializer.write(jvmInfo.name);
    serializer.write(jvmInfo.version);
    serializer.write(jvmInfo.vendor);
  }

  public static JvmInfo deserialize(ByteBuffer buf) {
    return new JvmInfo(
        Serializer.readString(buf), Serializer.readString(buf), Serializer.readString(buf));
  }
}
