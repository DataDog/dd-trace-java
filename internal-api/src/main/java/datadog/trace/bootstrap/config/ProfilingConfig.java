package datadog.trace.bootstrap.config;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Provides;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import javax.inject.Named;

@Component(
    modules = {
      CommonConfig.Module.class,
      ProfilingConfig.Module.class,
    })
interface ProfilingConfig {

  @Named("serviceName")
  String serviceName();

  @Named("profilingEnabled")
  boolean enabled();

  @Named("profilingStartDelay")
  int startDelay();

  @dagger.Module
  class Module {

    @Provides
    @Named("profilingEnabled")
    boolean enabled(ConfigProvider configProvider) {
      return configProvider.getBoolean(PROFILING_ENABLED, ConfigDefaults.DEFAULT_PROFILING_ENABLED);
    }

    @Provides
    @Named("profilingStartDelay")
    int startDelay(ConfigProvider configProvider) {
      return configProvider.getInteger(
          PROFILING_START_DELAY, ConfigDefaults.DEFAULT_PROFILING_START_DELAY);
    }
  }

  @Component.Builder
  interface Builder {

    @BindsInstance
    Builder configProvider(ConfigProvider configProvider);

    ProfilingConfig build();
  }
}
