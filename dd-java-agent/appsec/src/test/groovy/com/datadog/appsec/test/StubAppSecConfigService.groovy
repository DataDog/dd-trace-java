package com.datadog.appsec.test

import com.datadog.appsec.config.AppSecConfig
import com.datadog.appsec.config.AppSecConfigDeserializer
import com.datadog.appsec.config.AppSecConfigService
import com.datadog.appsec.config.TraceSegmentPostProcessor

class StubAppSecConfigService implements AppSecConfigService {
  Map<String, SubconfigListener> listeners = [:]

  Map<String, AppSecConfig> lastConfig
  final String location
  final traceSegmentPostProcessors = []

  private final Map hardcodedConfig

  StubAppSecConfigService(String location = "test_multi_config.json") {
    this.location = location
  }

  StubAppSecConfigService(Map config) {
    this.hardcodedConfig = config
  }

  @Override
  void init() {
    if (hardcodedConfig) {
      lastConfig = hardcodedConfig
    } else {
      def loader = getClass().classLoader
      def stream = loader.getResourceAsStream(location)
      lastConfig = Collections.singletonMap('waf',
        AppSecConfigDeserializer.INSTANCE.deserialize(stream))
    }
  }

  @Override
  Optional<AppSecConfig> addSubConfigListener(String key, SubconfigListener listener) {
    listeners[key] = listener

    Optional.ofNullable(lastConfig[key])
  }

  @Override
  void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor) {
    traceSegmentPostProcessors << interceptor
  }

  @Override
  void close() {}
}
