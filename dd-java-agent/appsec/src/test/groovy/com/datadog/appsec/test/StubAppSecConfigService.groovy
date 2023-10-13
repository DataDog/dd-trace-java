package com.datadog.appsec.test

import com.datadog.appsec.config.AppSecConfigDeserializer
import com.datadog.appsec.config.AppSecConfigService
import com.datadog.appsec.config.AppSecModuleConfigurer
import com.datadog.appsec.config.CurrentAppSecConfig
import com.datadog.appsec.config.TraceSegmentPostProcessor

class StubAppSecConfigService implements AppSecConfigService, AppSecConfigService.TransactionalAppSecModuleConfigurer {
  Map<String, AppSecModuleConfigurer.SubconfigListener> listeners = [:]

  Map<String, Object> lastConfig
  final String location
  final traceSegmentPostProcessors = []

  private final Map hardcodedConfig

  StubAppSecConfigService(String location = "test_multi_config.json") {
    this.location = location
  }

  StubAppSecConfigService(Map config) {
    this.hardcodedConfig = config
  }

  CurrentAppSecConfig getCurrentAppSecConfig() {
    lastConfig['waf']
  }

  @Override
  void init() {
    if (hardcodedConfig) {
      lastConfig = hardcodedConfig
    } else {
      def loader = getClass().classLoader
      def stream = loader.getResourceAsStream(location)
      def casc = new CurrentAppSecConfig()
      casc.ddConfig = AppSecConfigDeserializer.INSTANCE.deserialize(stream)
      lastConfig = Collections.singletonMap('waf', casc)
    }
  }

  @Override
  Optional<Object> addSubConfigListener(String key, AppSecModuleConfigurer.SubconfigListener listener) {
    listeners[key] = listener

    Optional.ofNullable(lastConfig[key])
  }

  @Override
  void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor) {
    traceSegmentPostProcessors << interceptor
  }

  @Override
  void close() {}

  @Override
  TransactionalAppSecModuleConfigurer createAppSecModuleConfigurer() {
    this
  }

  @Override
  void commit() {
  }
}
