package datadog.trace.bootstrap.config;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class DaggerConfig {
  // volatile to allow config changes in tests
  private static volatile ConfigComponent config;

  static {
    ConfigProvider configProvider = ConfigProvider.createDefault();

    TracingConfig tracingConfig =
        DaggerTracingConfig.builder().configProvider(configProvider).build();

    ProfilingConfig profilingConfig =
        DaggerProfilingConfig.builder().configProvider(configProvider).build();

    config =
        DaggerConfigComponent.builder()
            .tracingConfig(tracingConfig)
            .profilingConfig(profilingConfig)
            .build();
  }

  public static ConfigComponent get() {
    return config;
  }
}
