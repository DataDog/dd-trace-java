package com.datadog.appsec.test

import com.datadog.appsec.config.AppSecConfigService
import com.datadog.appsec.config.AppSecConfigServiceImpl
import com.squareup.moshi.Moshi

class StubAppSecConfigService implements AppSecConfigService {
  Map<String, SubconfigListener> listeners = [:]

  Map<String, Object> lastConfig

  StubAppSecConfigService(String location = AppSecConfigServiceImpl.DEFAULT_CONFIG_LOCATION) {
    def loader = getClass().classLoader
    def stream = loader.getResourceAsStream(location)
    def adapter = new Moshi.Builder().build().adapter(Map)
    lastConfig = adapter.fromJson(stream.text)
  }

  StubAppSecConfigService(Map config) {
    lastConfig = config
  }

  @Override
  void init() {}

  @Override
  Optional<Object> addSubConfigListener(String key, SubconfigListener listener) {
    listeners[key] = listener

    Optional.ofNullable(lastConfig[key])
  }

  @Override
  void close() {}
}
