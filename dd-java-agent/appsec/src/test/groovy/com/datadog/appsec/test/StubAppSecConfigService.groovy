package com.datadog.appsec.test

import com.datadog.appsec.config.AppSecConfigService
import com.datadog.appsec.config.AppSecConfigServiceImpl
import com.squareup.moshi.Moshi

class StubAppSecConfigService implements AppSecConfigService {
  Map<String, SubconfigListener> listeners = [:]

  Map<String, Object> lastConfig
  final String location

  private final Map hardcodedConfig

  StubAppSecConfigService(String location = AppSecConfigServiceImpl.DEFAULT_CONFIG_LOCATION) {
    this.location = location
  }

  StubAppSecConfigService(Map config) {
    this.hardcodedConfig = config
  }

  @Override
  void init(boolean initFleetService) {
    if (hardcodedConfig) {
      lastConfig = hardcodedConfig
    } else {
      def loader = getClass().classLoader
      def stream = loader.getResourceAsStream(location)
      def adapter = new Moshi.Builder().build().adapter(Map)
      lastConfig = adapter.fromJson(stream.text)
    }
  }

  @Override
  Optional<Object> addSubConfigListener(String key, SubconfigListener listener) {
    listeners[key] = listener

    Optional.ofNullable(lastConfig[key])
  }

  @Override
  void close() {}
}
