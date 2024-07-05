package datadog.trace.civisibility.config;

import datadog.trace.civisibility.ipc.Serializer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class JvmInfo {

  public static final JvmInfo CURRENT_JVM =
      new JvmInfo(
          System.getProperty("java.runtime.name"),
          System.getProperty("java.version"),
          System.getProperty("java.class.version"),
          System.getProperty("java.vendor"),
          System.getProperty("java.home"));

  private static final int JAVA_8_CLASS_VERSION = 52;

  private final String name;
  private final String version;
  private final int majorClassVersion;
  private final String vendor;
  private final Path home;

  public JvmInfo(String name, String version, String classVersion, String vendor, String home) {
    this.name = name;
    this.version = version;
    this.vendor = vendor;
    this.home = Paths.get(home);

    int majorClassVersion;
    try {
      String[] classVersionTokens = classVersion.split("\\.");
      majorClassVersion = Integer.parseInt(classVersionTokens[0]);
    } catch (Exception e) {
      majorClassVersion = -1;
    }
    this.majorClassVersion = majorClassVersion;
  }

  private JvmInfo(String name, String version, int majorClassVersion, String vendor, Path home) {
    this.name = name;
    this.version = version;
    this.vendor = vendor;
    this.home = home;
    this.majorClassVersion = majorClassVersion;
  }

  public String getName() {
    return name;
  }

  public String getVersion() {
    return version;
  }

  public int getMajorClassVersion() {
    return majorClassVersion;
  }

  public String getVendor() {
    return vendor;
  }

  public Path getHome() {
    return home;
  }

  public boolean isModular() {
    return majorClassVersion > JAVA_8_CLASS_VERSION;
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
        && Objects.equals(majorClassVersion, jvmInfo.majorClassVersion)
        && Objects.equals(vendor, jvmInfo.vendor)
        && Objects.equals(home, jvmInfo.home);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, version, majorClassVersion, vendor, home);
  }

  @Override
  public String toString() {
    return "JvmInfo{"
        + "name='"
        + name
        + '\''
        + ", version='"
        + version
        + ", majorClassVersion='"
        + majorClassVersion
        + '\''
        + ", vendor='"
        + vendor
        + '\''
        + ", home='"
        + home
        + '\''
        + '}';
  }

  public static void serialize(Serializer serializer, JvmInfo jvmInfo) {
    serializer.write(jvmInfo.name);
    serializer.write(jvmInfo.version);
    serializer.write(jvmInfo.majorClassVersion);
    serializer.write(jvmInfo.vendor);
    serializer.write(String.valueOf(jvmInfo.home));
  }

  public static JvmInfo deserialize(ByteBuffer buf) {
    String name = Serializer.readString(buf);
    String version = Serializer.readString(buf);
    int majorClassVersion = Serializer.readInt(buf);
    String vendor = Serializer.readString(buf);
    String home = Serializer.readString(buf);
    return new JvmInfo(
        name, version, majorClassVersion, vendor, home != null ? Paths.get(home) : null);
  }
}
