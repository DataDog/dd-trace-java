package datadog.trace.bootstrap.config;

import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;

import dagger.Provides;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import javax.inject.Named;

interface CommonConfig {

  @dagger.Module
  class Module {

    @Provides
    @Named("serviceName")
    String serviceName(ConfigProvider configProvider) {
      return configProvider.getString(SERVICE_NAME, ConfigDefaults.DEFAULT_SERVICE_NAME);
    }
  }
}
