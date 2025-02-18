import java.util.Map;

class JavaConfigurator implements AutoCloseable {

  class StableConfig {
    public String config_id;
    public Map<String, String> local_configuration;
    public Map<String, String> fleet_configuration;
  }

  static {
    // This loads the shared object that we'll be creating.
    System.loadLibrary("datadog_library_config_java");
  }

  private static native long new_configurator(boolean debug_logs);

  private static native void override_local_path(long configurator, String local_path);

  private static native void override_fleet_path(long configurator, String fleet_path);

  private static native void drop(long configurator);

  private static native void get_configuration(long configurator, Object config);

  private long configurator;

  public JavaConfigurator(boolean debug_logs) {
    configurator = new_configurator(debug_logs);
  }

  public void setLocalPath(String localPath) {
    override_local_path(configurator, localPath);
  }

  public void setFleetPath(String fleetPath) {
    override_fleet_path(configurator, fleetPath);
  }

  public StableConfig getConfiguration() {
    StableConfig cfg = new StableConfig();
    get_configuration(configurator, cfg);
    return cfg;
  }

  @Override
  public void close() {
    if (configurator != 0) {
      drop(configurator);
      configurator = 0;
    }
  }
}
