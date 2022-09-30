package com.datadog.appsec.config;

import static com.datadog.appsec.util.StandardizedLogging.RulesInvalidReason.INVALID_JSON_FILE;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.config.AppSecModuleConfigurer.SubconfigListener;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecConfigServiceImpl implements AppSecConfigService {

  private static final Logger log = LoggerFactory.getLogger(AppSecConfigServiceImpl.class);

  private static final String DEFAULT_CONFIG_LOCATION = "default_config.json";

  // for new subconfig subscribers
  private final ConcurrentHashMap<String, Object> lastConfig = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, SubconfigListener> subconfigListeners =
      new ConcurrentHashMap<>();
  private final Config tracerConfig;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors = new ArrayList<>();
  private final ConfigurationPoller configurationPoller;
  private final AppSecModuleConfigurer.Reconfiguration reconfiguration;

  private boolean hasUserWafConfig;

  public AppSecConfigServiceImpl(
      Config tracerConfig,
      @Nullable ConfigurationPoller configurationPoller,
      AppSecModuleConfigurer.Reconfiguration reconfig) {
    this.tracerConfig = tracerConfig;
    this.configurationPoller = configurationPoller;
    this.reconfiguration = reconfig;
  }

  private void subscribeConfigurationPoller() {
    // see also close() method
    this.configurationPoller.addListener(
        Product.ASM_DD,
        AppSecConfigDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          if (newConfig == null) {
            log.warn("AppSec configuration was pulled out by remote config. This has no effect");
            return true;
          }
          Map<String, Object> configMap = Collections.singletonMap("waf", newConfig);
          this.lastConfig.put("waf", newConfig);
          distributeSubConfigurations(configMap, reconfiguration);
          log.info(
              "New AppSec configuration has been applied. AppSec status: {}",
              AppSecSystem.ACTIVE ? "active" : "inactive");
          return true;
        });
    this.configurationPoller.addListener(
        Product.ASM_DATA,
        AppSecDataDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          if (newConfig == null) {
            newConfig = Collections.emptyList();
          }
          Map<String, Object> wafDataConfigMap = Collections.singletonMap("waf_data", newConfig);
          this.lastConfig.put("waf_data", wafDataConfigMap);
          distributeSubConfigurations(wafDataConfigMap, reconfiguration);
          return true;
        });

    this.configurationPoller.addFeaturesListener(
        "asm",
        AppSecFeaturesDeserializer.INSTANCE,
        (product, newConfig, hinter) -> {
          if (AppSecSystem.ACTIVE != newConfig.enabled) {
            log.warn("AppSec {} (runtime)", newConfig.enabled ? "enabled" : "disabled");
          }
          AppSecSystem.ACTIVE = newConfig.enabled;
          return true;
        });
  }

  private void distributeSubConfigurations(
      Map<String, Object> newConfig, AppSecModuleConfigurer.Reconfiguration reconfiguration) {
    for (Map.Entry<String, SubconfigListener> entry : subconfigListeners.entrySet()) {
      String key = entry.getKey();
      if (!newConfig.containsKey(key)) {
        continue;
      }
      SubconfigListener listener = entry.getValue();
      try {
        listener.onNewSubconfig(newConfig.get(key), reconfiguration);
      } catch (Exception rte) {
        log.warn("Error updating configuration of app sec module listening on key " + key, rte);
      }
    }
  }

  @Override
  public void init() {
    AppSecConfig wafConfig;
    hasUserWafConfig = false;
    try {
      wafConfig = loadUserWafConfig(tracerConfig);
    } catch (Exception e) {
      log.error("Error loading user-provided config", e);
      throw new AbortStartupException("Error loading user-provided config", e);
    }
    if (wafConfig == null) {
      try {
        wafConfig = loadDefaultWafConfig();
      } catch (IOException e) {
        log.error("Error loading default config", e);
        throw new AbortStartupException("Error loading default config", e);
      }
    } else {
      hasUserWafConfig = true;
    }
    lastConfig.put("waf", wafConfig);
  }

  public void maybeStartConfigPolling() {
    if (this.configurationPoller != null) {
      if (hasUserWafConfig) {
        log.info("AppSec will not use remote config because there is a custom user configuration");
      } else {
        subscribeConfigurationPoller();
      }
    }
  }

  public List<TraceSegmentPostProcessor> getTraceSegmentPostProcessors() {
    return traceSegmentPostProcessors;
  }

  /**
   * Implementation of {@link AppSecModuleConfigurer} that solves two problems: - Avoids the
   * submodules receiving configuration changes before their initial config is completed. - Avoid
   * submodules being (partially) subscribed to configuration changes even if their initial config
   * failed.
   */
  private class TransactionalAppSecModuleConfigurerImpl
      implements TransactionalAppSecModuleConfigurer {
    private Map<String, SubconfigListener> listenerMap = new HashMap<>();
    private List<TraceSegmentPostProcessor> postProcessors = new ArrayList<>();

    @Override
    public Optional<Object> addSubConfigListener(String key, SubconfigListener listener) {
      listenerMap.put(key, listener);
      return Optional.ofNullable(lastConfig.get(key));
    }

    @Override
    public void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor) {
      postProcessors.add(interceptor);
    }

    public void commit() {
      AppSecConfigServiceImpl.this.subconfigListeners.putAll(listenerMap);
      AppSecConfigServiceImpl.this.traceSegmentPostProcessors.addAll(postProcessors);
    }
  }

  @Override
  public TransactionalAppSecModuleConfigurer createAppSecModuleConfigurer() {
    return new TransactionalAppSecModuleConfigurerImpl();
  }

  private static AppSecConfig loadDefaultWafConfig() throws IOException {
    try (InputStream is =
        AppSecConfigServiceImpl.class
            .getClassLoader()
            .getResourceAsStream(DEFAULT_CONFIG_LOCATION)) {
      if (is == null) {
        throw new IOException("Resource " + DEFAULT_CONFIG_LOCATION + " not found");
      }

      AppSecConfig ret = AppSecConfigDeserializer.INSTANCE.deserialize(is);

      StandardizedLogging._initialConfigSourceAndLibddwafVersion(log, "<bundled config>");
      if (log.isInfoEnabled()) {
        StandardizedLogging.numLoadedRules(log, "<bundled config>", countRules(ret));
      }

      return ret;
    }
  }

  private static AppSecConfig loadUserWafConfig(Config tracerConfig) throws IOException {
    String filename = tracerConfig.getAppSecRulesFile();
    if (filename == null) {
      return null;
    }
    try (InputStream is = new FileInputStream(filename)) {
      AppSecConfig ret = AppSecConfigDeserializer.INSTANCE.deserialize(is);

      StandardizedLogging._initialConfigSourceAndLibddwafVersion(log, filename);
      if (log.isInfoEnabled()) {
        StandardizedLogging.numLoadedRules(log, filename, countRules(ret));
      }

      return ret;
    } catch (FileNotFoundException fnfe) {
      StandardizedLogging.rulesFileNotFound(log, filename);
      throw fnfe;
    } catch (IOException ioe) {
      StandardizedLogging.rulesFileInvalid(log, filename, INVALID_JSON_FILE);
      throw ioe;
    }
  }

  private static int countRules(AppSecConfig config) {
    return config.getRules().size();
  }

  @Override
  public void close() {
    if (this.configurationPoller == null) {
      return;
    }
    this.configurationPoller.removeListener(Product.ASM_DD);
    this.configurationPoller.removeListener(Product.ASM_DATA);
    this.configurationPoller.removeFeaturesListener("asm");
    this.configurationPoller.stop();
  }
}
