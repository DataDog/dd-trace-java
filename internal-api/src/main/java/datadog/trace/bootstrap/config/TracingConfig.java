package datadog.trace.bootstrap.config;

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Provides;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import javax.inject.Named;

@Component(
    modules = {
      CommonConfig.Module.class,
      TracingConfig.Module.class,
    })
interface TracingConfig {

  @Named("serviceName")
  String serviceName();

  @Named("tracingEnabled")
  boolean enabled();

  @dagger.Module
  class Module {

    @Provides
    @Named("tracingEnabled")
    boolean enabled(ConfigProvider configProvider) {
      return configProvider.getBoolean(TRACE_ENABLED, ConfigDefaults.DEFAULT_TRACE_ENABLED);
    }
  }

  @Component.Builder
  interface Builder {

    @BindsInstance
    Builder configProvider(ConfigProvider configProvider);

    TracingConfig build();
  }
}
