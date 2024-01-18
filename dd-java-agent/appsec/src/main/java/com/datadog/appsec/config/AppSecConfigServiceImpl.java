package com.datadog.appsec.config;

import static com.datadog.appsec.util.StandardizedLogging.RulesInvalidReason.INVALID_JSON_FILE;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_ACTIVATION;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_CUSTOM_RULES;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_DD_RULES;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_EXCLUSIONS;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_IP_BLOCKING;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_REQUEST_BLOCKING;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_TRUSTED_IPS;
import static datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.CAPABILITY_ASM_USER_BLOCKING;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.config.AppSecModuleConfigurer.SubconfigListener;
import com.datadog.appsec.config.CurrentAppSecConfig.DirtyStatus;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.remoteconfig.ConfigurationEndListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
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
  private static AppSecConfig DEFAULT_WAF_CONFIG;

  private final ConfigurationPoller configurationPoller;

  // the only thread modifying currentAppSecConfig is the RC thread
  // However, the initial state is set up by another thread, and this needs to be visible to the RC
  // thread
  private CurrentAppSecConfig currentAppSecConfig;
  private volatile boolean initialized;

  // for new subconfig subscribers
  private final ConcurrentHashMap<String, Object> lastConfig = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, SubconfigListener> subconfigListeners =
      new ConcurrentHashMap<>();
  private final Config tracerConfig;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors = new ArrayList<>();
  private final AppSecModuleConfigurer.Reconfiguration reconfiguration;

  private final ConfigurationEndListener applyWAFChangesAsListener = this::applyWAFChanges;

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
    if (tracerConfig.getAppSecActivation() == ProductActivation.ENABLED_INACTIVE) {
      subscribeActivation();
    } else {
      log.debug("Will not subscribe to ASM_FEATURES (AppSec explicitly enabled)");
    }
    if (!hasUserWafConfig) {
      subscribeRulesAndData();
    } else {
      log.debug("Will not subscribe to ASM, ASM_DD and ASM_DATA (AppSec custom rules in use)");
    }

    this.configurationPoller.addConfigurationEndListener(applyWAFChangesAsListener);

    this.configurationPoller.addCapabilities(
        CAPABILITY_ASM_DD_RULES
            | CAPABILITY_ASM_IP_BLOCKING
            | CAPABILITY_ASM_EXCLUSIONS
            | CAPABILITY_ASM_REQUEST_BLOCKING
            | CAPABILITY_ASM_USER_BLOCKING
            | CAPABILITY_ASM_CUSTOM_RULES
            | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
            | CAPABILITY_ASM_TRUSTED_IPS);
  }

  private void subscribeRulesAndData() {
    this.configurationPoller.addListener(
        Product.ASM_DD,
        AppSecConfigDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          // read initialized so that the state is currentAppSecConfig is visible
          if (!initialized) {
            throw new IllegalStateException();
          }
          if (newConfig == null) {
            if (DEFAULT_WAF_CONFIG == null) {
              throw new IllegalStateException("Expected default waf config to be available");
            }
            log.debug(
                "AppSec config given by remote config was pulled. Restoring default WAF config");
            newConfig = DEFAULT_WAF_CONFIG;
          }
          this.currentAppSecConfig.setDdConfig(newConfig);
          // base rules can contain all rules/data/exclusions/etc
          this.currentAppSecConfig.dirtyStatus.markAllDirty();
        });
    this.configurationPoller.addListener(
        Product.ASM_DATA,
        AppSecDataDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          if (!initialized) {
            throw new IllegalStateException();
          }
          if (newConfig == null) {
            currentAppSecConfig.mergedAsmData.removeConfig(configKey);
          } else {
            currentAppSecConfig.mergedAsmData.addConfig(configKey, newConfig);
          }
          this.currentAppSecConfig.dirtyStatus.data = true;
        });
    this.configurationPoller.addListener(
        Product.ASM,
        AppSecUserConfigDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          if (!initialized) {
            throw new IllegalStateException();
          }
          DirtyStatus dirtyStatus;
          if (newConfig == null) {
            dirtyStatus = currentAppSecConfig.userConfigs.removeConfig(configKey);
          } else {
            AppSecUserConfig userCfg = newConfig.build(configKey);
            dirtyStatus = currentAppSecConfig.userConfigs.addConfig(userCfg);
          }

          this.currentAppSecConfig.dirtyStatus.mergeFrom(dirtyStatus);
        });
  }

  private void subscribeActivation() {
    this.configurationPoller.addListener(
        Product.ASM_FEATURES,
        "asm_features_activation",
        AppSecFeaturesDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          if (!initialized) {
            throw new IllegalStateException();
          }
          final boolean newState =
              newConfig != null && newConfig.asm != null && newConfig.asm.enabled;
          if (AppSecSystem.isActive() != newState) {
            log.info("AppSec {} (runtime)", newState ? "enabled" : "disabled");
            AppSecSystem.setActive(newState);
            if (AppSecSystem.isActive()) {
              // On remote activation, we need to re-distribute the last known configuration.
              // This may trigger initializations, including PowerWAF if it was lazy loaded.
              this.currentAppSecConfig.dirtyStatus.markAllDirty();
            }
          }
        });
    this.configurationPoller.addCapabilities(CAPABILITY_ASM_ACTIVATION);
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
        log.warn("Error updating configuration of app sec module listening on key {}", key, rte);
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
    this.currentAppSecConfig = new CurrentAppSecConfig();
    this.currentAppSecConfig.setDdConfig(wafConfig);
    this.lastConfig.put("waf", this.currentAppSecConfig);
    this.initialized = true;
  }

  public void maybeSubscribeConfigPolling() {
    if (this.configurationPoller != null) {
      if (hasUserWafConfig
          && tracerConfig.getAppSecActivation() == ProductActivation.FULLY_ENABLED) {
        log.info(
            "AppSec will not use remote config because "
                + "there is a custom user configuration and AppSec is explicitly enabled");
      } else {
        subscribeConfigurationPoller();
      }
    } else {
      log.info("Remote config is disabled; AppSec will not be able to use it");
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
    private final Map<String, SubconfigListener> listenerMap = new HashMap<>();
    private final List<TraceSegmentPostProcessor> postProcessors = new ArrayList<>();

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

      DEFAULT_WAF_CONFIG = ret;
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
    return config.getNumberOfRules();
  }

  @Override
  public void close() {
    if (this.configurationPoller == null) {
      return;
    }
    this.configurationPoller.removeCapabilities(
        CAPABILITY_ASM_ACTIVATION
            | CAPABILITY_ASM_DD_RULES
            | CAPABILITY_ASM_IP_BLOCKING
            | CAPABILITY_ASM_EXCLUSIONS
            | CAPABILITY_ASM_REQUEST_BLOCKING
            | CAPABILITY_ASM_USER_BLOCKING
            | CAPABILITY_ASM_CUSTOM_RULES
            | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
            | CAPABILITY_ASM_TRUSTED_IPS
            | CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE);
    this.configurationPoller.removeListeners(Product.ASM_DD);
    this.configurationPoller.removeListeners(Product.ASM_DATA);
    this.configurationPoller.removeListeners(Product.ASM);
    this.configurationPoller.removeListeners(Product.ASM_FEATURES);
    this.configurationPoller.removeConfigurationEndListener(applyWAFChangesAsListener);
    this.configurationPoller.stop();
  }

  private void applyWAFChanges() {
    if (!AppSecSystem.isActive() || !currentAppSecConfig.dirtyStatus.isAnyDirty()) {
      return;
    }

    distributeSubConfigurations(
        Collections.singletonMap("waf", currentAppSecConfig), reconfiguration);
    currentAppSecConfig.dirtyStatus.clearDirty();
  }
}
