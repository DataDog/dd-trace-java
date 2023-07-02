package datadog.trace.civisibility.config;

public class JvmInfo {

  public static final JvmInfo CURRENT_JVM =
      new JvmInfo(
          System.getProperty("java.runtime.name"),
          System.getProperty("java.version"),
          System.getProperty("java.vendor"));

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
}
