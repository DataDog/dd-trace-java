package com.datadog.appsec.test

import com.datadog.appsec.config.AppSecConfig
import com.datadog.appsec.config.AppSecConfigDeserializer
import com.datadog.appsec.config.AppSecConfigService
import com.datadog.appsec.config.AppSecData
import com.datadog.appsec.config.AppSecModuleConfigurer
import com.datadog.appsec.config.AppSecUserConfig
import com.datadog.appsec.config.CurrentAppSecConfig
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.ddwaf.WafBuilder
import com.datadog.ddwaf.exception.InvalidRuleSetException
import datadog.remoteconfig.ConfigurationChangesTypedListener

class StubAppSecConfigService implements AppSecConfigService, AppSecConfigService.TransactionalAppSecModuleConfigurer {
  Map<String, AppSecModuleConfigurer.SubconfigListener> listeners = [:]

  Map<String, Object> lastConfig
  final String location
  final traceSegmentPostProcessors = []
  WafBuilder wafBuilder
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
  void init(WafBuilder wafBuilder) {
    this.wafBuilder = wafBuilder
    if (hardcodedConfig) {
      lastConfig = hardcodedConfig
    } else {
      def loader = getClass().classLoader
      def stream = loader.getResourceAsStream(location)
      def casc = new CurrentAppSecConfig()
      casc.ddConfig = AppSecConfigDeserializer.INSTANCE.deserialize(stream)
      if(casc.ddConfig == null || casc.ddConfig.rawConfig == null) {
        wafBuilder.removeConfig("waf")
      } else {
        wafBuilder.addOrUpdateConfig("waf", casc.ddConfig.rawConfig)
      }
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

  private ConfigurationChangesTypedListener<AppSecUserConfig.Builder> asmTypedListener(
  WafBuilder wafBuilder) {
    return (configKey, newConfig, hinter) -> {
      CurrentAppSecConfig.DirtyStatus dirtyStatus
      if (newConfig == null) {
        dirtyStatus = currentAppSecConfig.userConfigs.removeConfig(configKey)
        wafBuilder.removeConfig(configKey)
      } else {
        AppSecUserConfig userCfg = newConfig.build(configKey)
        dirtyStatus = currentAppSecConfig.userConfigs.addConfig(userCfg)
        try {
          wafBuilder.addOrUpdateConfig(configKey, newConfig.getRawConfig())
        } catch (InvalidRuleSetException e) {
          throw new RuntimeException(e)
        }
      }

      this.currentAppSecConfig.dirtyStatus.mergeFrom(dirtyStatus)
    }
  }

  private ConfigurationChangesTypedListener<AppSecData> asmDataTypedListener(
  WafBuilder wafBuilder) {
    return (configKey, newConfig, hinter) -> {
      if (newConfig == null) {
        currentAppSecConfig.mergedAsmData.removeConfig(configKey)
        wafBuilder.removeConfig(configKey)
      } else {
        currentAppSecConfig.mergedAsmData.addConfig(configKey, newConfig)
        try {
          wafBuilder.addOrUpdateConfig(configKey, newConfig.getRawConfig())
        } catch (InvalidRuleSetException e) {
          throw new RuntimeException(e)
        }
      }
      this.currentAppSecConfig.dirtyStatus.data = true
    }
  }

  private ConfigurationChangesTypedListener<AppSecConfig> asmDDTypedListener(
  WafBuilder wafBuilder) {
    return (configKey, newConfig, hinter) -> {
      if (newConfig == null || newConfig.getRawConfig() == null) {
        wafBuilder.removeConfig(configKey)
      } else {
        try {
          wafBuilder.addOrUpdateConfig(configKey, newConfig.getRawConfig())
        } catch (InvalidRuleSetException e) {
          throw new RuntimeException(e)
        }
      }
      this.currentAppSecConfig.setDdConfig(newConfig)
      // base rules can contain all rules/data/exclusions/etc
      this.currentAppSecConfig.dirtyStatus.markAllDirty()
    }
  }
}
