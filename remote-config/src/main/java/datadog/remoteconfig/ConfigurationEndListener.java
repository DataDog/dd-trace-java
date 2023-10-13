package datadog.remoteconfig;

@FunctionalInterface
public interface ConfigurationEndListener {
  void onConfigurationEnd();
}
