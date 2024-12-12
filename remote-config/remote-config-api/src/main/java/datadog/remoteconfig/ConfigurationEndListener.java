package datadog.remoteconfig;

/** This interface defines a listener of the configuration change. */
@FunctionalInterface
public interface ConfigurationEndListener {
  /** Notifies the listener that new configuration was applied. */
  void onConfigurationEnd();
}
