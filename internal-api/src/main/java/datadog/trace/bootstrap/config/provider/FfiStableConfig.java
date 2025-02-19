package datadog.trace.bootstrap.config.provider;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FfiStableConfig implements AutoCloseable {
  private static native long new_configurator(boolean debug_logs);
  private static native void override_local_path(long configurator, String local_path);
  private static native void override_fleet_path(long configurator, String fleet_path);
  private static native void drop_configurator(long configurator);
  private static native void get_configuration(long configurator, Object config);
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);
  private long configurator;

  public class StableConfigResult {
    public String config_id;
    public Map<String, String> local_configuration;
    public Map<String, String> fleet_configuration;
  }

  static { 
    // TODO: load from jar similar to https://github.com/DataDog/libddwaf-java/blob/8e32a313705a515c72d388338a1dde7cddab9678/src/main/java/io/sqreen/powerwaf/NativeLibLoader.java#L32
    try {
      System.load("/root/dd/dd-trace-java/internal-api/src/main/java/datadog/trace/bootstrap/config/provider/library-config-java/target/release/libdatadog_library_config_java.so");
    } catch (Throwable t) {
      log.warn("Failed to load libdatadog_library_config_java. Err: {}", t.getMessage());
    }
  }

  public FfiStableConfig(boolean debug_logs) {
    configurator = new_configurator(debug_logs);
  }

  public void setLocalPath(String localPath) {
    override_local_path(configurator, localPath);
  }

  public void setFleetPath(String fleetPath) {
    override_fleet_path(configurator, fleetPath);
  }

  public StableConfigResult getConfiguration() {
    StableConfigResult cfg = new StableConfigResult();
    get_configuration(configurator, cfg);
    return cfg;
  }

  @Override
  public void close() {
    if (configurator != 0) {
      drop_configurator(configurator);
      configurator = 0;
    }
  }
}
