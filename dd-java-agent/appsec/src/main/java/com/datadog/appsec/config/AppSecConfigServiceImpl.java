package com.datadog.appsec.config;

import static com.datadog.appsec.util.StandardizedLogging.RulesInvalidReason.INVALID_JSON_FILE;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_ACTIVATION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_DATA_SCANNERS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_DD_MULTICONFIG;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_DD_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXCLUSIONS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXCLUSION_DATA;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXTENDED_DATA_COLLECTION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_HEADER_FINGERPRINT;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_IP_BLOCKING;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_NETWORK_FINGERPRINT;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_PROCESSOR_OVERRIDES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_CMDI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_LFI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SHI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SQLI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SSRF;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_REQUEST_BLOCKING;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SESSION_FINGERPRINT;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_TRACE_TAGGING_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_TRUSTED_IPS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_USER_BLOCKING;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ENDPOINT_FINGERPRINT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.config.AppSecModuleConfigurer.SubconfigListener;
import com.datadog.appsec.ddwaf.WAFInitializationResultReporter;
import com.datadog.appsec.ddwaf.WAFStatsReporter;
import com.datadog.appsec.ddwaf.WafInitialization;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.StandardizedLogging;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafConfig;
import com.datadog.ddwaf.WafDiagnostics;
import com.datadog.ddwaf.exception.InvalidRuleSetException;
import com.datadog.ddwaf.exception.UnclassifiedWafException;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationEndListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecConfigServiceImpl implements AppSecConfigService {

  private static final Logger log = LoggerFactory.getLogger(AppSecConfigServiceImpl.class);

  private static final String DEFAULT_CONFIG_LOCATION = "default_config.json";

  private final ConfigurationPoller configurationPoller;
  private WafBuilder wafBuilder;

  private final MergedAsmFeatures mergedAsmFeatures = new MergedAsmFeatures();

  private final ConcurrentHashMap<String, SubconfigListener> subconfigListeners =
      new ConcurrentHashMap<>();
  private final Config tracerConfig;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors = new ArrayList<>();
  private final AppSecModuleConfigurer.Reconfiguration reconfiguration;

  private final ConfigurationEndListener applyRemoteConfigListener =
      this::applyRemoteConfigListener;
  private final WAFInitializationResultReporter initReporter =
      new WAFInitializationResultReporter();
  private final WAFStatsReporter statsReporter = new WAFStatsReporter();

  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private boolean hasUserWafConfig;
  private boolean defaultConfigActivated;
  private final AtomicBoolean subscribedToRulesAndData = new AtomicBoolean();
  private final AtomicBoolean subscribedToSCA = new AtomicBoolean();
  private final Set<String> usedDDWafConfigKeys =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final Set<String> ignoredConfigKeys =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final String DEFAULT_WAF_CONFIG_RULE = "ASM_DD/default";
  private String currentRuleVersion;
  private List<AppSecModule> modulesToUpdateVersionIn;
  private volatile AppSecSCAConfig currentSCAConfig;

  public AppSecConfigServiceImpl(
      Config tracerConfig,
      ConfigurationPoller configurationPoller,
      AppSecModuleConfigurer.Reconfiguration reconfig) {
    this.tracerConfig = tracerConfig;
    this.configurationPoller = configurationPoller;
    this.reconfiguration = reconfig;
    traceSegmentPostProcessors.add(initReporter);
    if (tracerConfig.isAppSecWafMetrics()) {
      traceSegmentPostProcessors.add(statsReporter);
    }
  }

  private void subscribeConfigurationPoller() {
    // see also close() method
    subscribeAsmFeatures();

    if (!hasUserWafConfig) {
      updateRulesAndDataSubscription();
    } else {
      log.debug("Will not subscribe to ASM, ASM_DD and ASM_DATA (AppSec custom rules in use)");
    }

    subscribeSCA();

    this.configurationPoller.addConfigurationEndListener(applyRemoteConfigListener);
  }

  private long getRulesAndDataCapabilities() {
    long capabilities =
        CAPABILITY_ASM_DD_RULES
            | CAPABILITY_ASM_DD_MULTICONFIG
            | CAPABILITY_ASM_IP_BLOCKING
            | CAPABILITY_ASM_EXCLUSIONS
            | CAPABILITY_ASM_EXCLUSION_DATA
            | CAPABILITY_ASM_REQUEST_BLOCKING
            | CAPABILITY_ASM_USER_BLOCKING
            | CAPABILITY_ASM_CUSTOM_RULES
            | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
            | CAPABILITY_ASM_TRUSTED_IPS
            | CAPABILITY_ASM_PROCESSOR_OVERRIDES
            | CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
            | CAPABILITY_ENDPOINT_FINGERPRINT
            | CAPABILITY_ASM_SESSION_FINGERPRINT
            | CAPABILITY_ASM_NETWORK_FINGERPRINT
            | CAPABILITY_ASM_HEADER_FINGERPRINT
            | CAPABILITY_ASM_TRACE_TAGGING_RULES
            | CAPABILITY_ASM_EXTENDED_DATA_COLLECTION;
    if (tracerConfig.isAppSecRaspEnabled()) {
      capabilities |= CAPABILITY_ASM_RASP_SQLI;
      capabilities |= CAPABILITY_ASM_RASP_SSRF;
      capabilities |= CAPABILITY_ASM_RASP_CMDI;
      capabilities |= CAPABILITY_ASM_RASP_SHI;
      // RASP LFI is only available in fully enabled mode as it's implemented using callsite
      // instrumentation
      if (tracerConfig.getAppSecActivation() == ProductActivation.FULLY_ENABLED) {
        capabilities |= CAPABILITY_ASM_RASP_LFI;
      }
    }
    return capabilities;
  }

  private void updateRulesAndDataSubscription() {
    if (hasUserWafConfig) {
      return; // do nothing if the customer has custom rules
    }
    if (AppSecSystem.isActive()) {
      subscribeRulesAndData();
    } else {
      unsubscribeRulesAndData();
    }
  }

  private void subscribeRulesAndData() {
    if (subscribedToRulesAndData.compareAndSet(false, true)) {
      this.configurationPoller.addListener(Product.ASM_DD, new AppSecConfigChangesDDListener());
      this.configurationPoller.addListener(Product.ASM_DATA, new AppSecConfigChangesListener());
      this.configurationPoller.addListener(Product.ASM, new AppSecConfigChangesListener());
      this.configurationPoller.addCapabilities(getRulesAndDataCapabilities());
    }
  }

  private void unsubscribeRulesAndData() {
    if (subscribedToRulesAndData.compareAndSet(true, false)) {
      this.configurationPoller.removeListeners(Product.ASM_DD);
      this.configurationPoller.removeListeners(Product.ASM_DATA);
      this.configurationPoller.removeListeners(Product.ASM);
      this.configurationPoller.removeCapabilities(getRulesAndDataCapabilities());
    }
  }

  public void modulesToUpdateVersionIn(List<AppSecModule> modules) {
    this.modulesToUpdateVersionIn = modules;
  }

  public String getCurrentRuleVersion() {
    return currentRuleVersion;
  }

  private class AppSecConfigChangesListener implements ProductListener {
    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
        throws IOException {
      if (content == null) {
        remove(configKey, pollingRateHinter);
        return;
      }
      final String key = configKey.toString();
      Map<String, Object> contentMap =
          (Map<String, Object>)
              ADAPTER.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
      if (contentMap == null || contentMap.isEmpty()) {
        ignoredConfigKeys.add(key);
      } else {
        ignoredConfigKeys.remove(key);
        try {
          beforeApply(key, contentMap);
          maybeInitializeDefaultConfig();
          handleWafUpdateResultReport(key, contentMap);
        } catch (AppSecModule.AppSecModuleActivationException e) {
          throw new RuntimeException(e);
        }
      }
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter)
        throws IOException {
      final String key = configKey.toString();
      if (ignoredConfigKeys.remove(key)) {
        return;
      }
      try {
        maybeInitializeDefaultConfig();
        wafBuilder.removeConfig(key);
        afterRemove(key);
      } catch (UnclassifiedWafException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void commit(PollingRateHinter pollingRateHinter) {
      // no action needed
    }

    protected void beforeApply(final String key, final Map<String, Object> contentMap) {}

    protected void afterRemove(final String key) {}
  }

  private class AppSecConfigChangesDDListener extends AppSecConfigChangesListener {
    @Override
    protected void beforeApply(final String key, final Map<String, Object> config) {
      if (defaultConfigActivated) { // if we get any config, remove the default one
        log.debug("Removing default config ASM_DD/default");
        try {
          wafBuilder.removeConfig(DEFAULT_WAF_CONFIG_RULE);
        } catch (UnclassifiedWafException e) {
          throw new RuntimeException(e);
        }
        defaultConfigActivated = false;
      }
      usedDDWafConfigKeys.add(key);
    }

    @Override
    protected void afterRemove(final String key) {
      usedDDWafConfigKeys.remove(key);
    }
  }

  private void handleWafUpdateResultReport(String configKey, Map<String, Object> rawConfig)
      throws AppSecModule.AppSecModuleActivationException {
    wafBuilder = getWafBuilder();
    if (modulesToUpdateVersionIn != null
        && !modulesToUpdateVersionIn.isEmpty()
        && !modulesToUpdateVersionIn.stream().findFirst().get().isWafBuilderSet()) {
      modulesToUpdateVersionIn.forEach(module -> module.setWafBuilder(wafBuilder));
    }
    try {
      WafDiagnostics wafDiagnostics = wafBuilder.addOrUpdateConfig(configKey, rawConfig);
      if (log.isInfoEnabled()) {
        StandardizedLogging.numLoadedRules(log, configKey, countRules(rawConfig));
      }

      // TODO: Send diagnostics via telemetry

      initReporter.setReportForPublication(wafDiagnostics);
      if (wafDiagnostics.rulesetVersion != null
          && !wafDiagnostics.rulesetVersion.isEmpty()
          && !wafDiagnostics.rules.getLoaded().isEmpty()
          && (!defaultConfigActivated || currentRuleVersion == null)) {
        currentRuleVersion = wafDiagnostics.rulesetVersion;
        statsReporter.setRulesVersion(currentRuleVersion);
        if (modulesToUpdateVersionIn != null) {
          modulesToUpdateVersionIn.forEach(module -> module.setRuleVersion(currentRuleVersion));
        }
      }
    } catch (InvalidRuleSetException e) {
      log.debug(
          "Invalid rule during waf config update for config key {}: {}",
          configKey,
          e.wafDiagnostics);
      if (e.wafDiagnostics.getNumConfigError() > 0) {
        WafMetricCollector.get().addWafConfigError(e.wafDiagnostics.getNumConfigError());
      }
      // TODO: Propagate diagostics back to remote config apply_error

      initReporter.setReportForPublication(e.wafDiagnostics);
      throw new RuntimeException(e);
    } catch (UnclassifiedWafException e) {
      log.debug("Error during waf config update for config key {}: {}", configKey, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  private void subscribeAsmFeatures() {
    this.configurationPoller.addListener(
        Product.ASM_FEATURES,
        AppSecFeaturesDeserializer.INSTANCE,
        (configKey, newConfig, hinter) -> {
          if (newConfig == null) {
            mergedAsmFeatures.removeConfig(configKey);
          } else {
            mergedAsmFeatures.addConfig(configKey, newConfig);
          }
        });
    if (tracerConfig.getAppSecActivation() == ProductActivation.ENABLED_INACTIVE) {
      this.configurationPoller.addCapabilities(CAPABILITY_ASM_ACTIVATION);
    } else {
      log.debug("Will not subscribe report CAPABILITY_ASM_ACTIVATION (AppSec explicitly enabled)");
    }
    this.configurationPoller.addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE);
  }

  /**
   * Subscribes to Supply Chain Analysis (SCA) configuration from Remote Config.
   * Receives instrumentation targets for vulnerability detection in third-party dependencies.
   */
  private void subscribeSCA() {
    if (subscribedToSCA.compareAndSet(false, true)) {
      log.debug("Subscribing to ASM_SCA Remote Config product");
      this.configurationPoller.addListener(
          Product.ASM_SCA,
          AppSecSCAConfigDeserializer.INSTANCE,
          (configKey, newConfig, hinter) -> {
            if (newConfig == null) {
              log.debug("Received removal for SCA config key: {}", configKey);
              currentSCAConfig = null;
              // TODO: Trigger retransformation to remove instrumentation when updater exists
            } else {
              log.debug(
                  "Received SCA config update for key: {} - enabled: {}, targets: {}",
                  configKey,
                  newConfig.enabled,
                  newConfig.instrumentationTargets != null
                      ? newConfig.instrumentationTargets.size()
                      : 0);
              currentSCAConfig = newConfig;
              // TODO: Trigger retransformation when AppSecInstrumentationUpdater exists
            }
          });
      this.configurationPoller.addCapabilities(CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION);
      log.info("Successfully subscribed to ASM_SCA Remote Config product");
    }
  }

  /**
   * Unsubscribes from SCA Remote Config product and clears current configuration.
   */
  private void unsubscribeSCA() {
    if (subscribedToSCA.compareAndSet(true, false)) {
      log.debug("Unsubscribing from ASM_SCA Remote Config product");
      this.configurationPoller.removeListeners(Product.ASM_SCA);
      this.configurationPoller.removeCapabilities(CAPABILITY_ASM_SCA_VULNERABILITY_DETECTION);
      currentSCAConfig = null;
      log.info("Successfully unsubscribed from ASM_SCA Remote Config product");
    }
  }

  private void distributeSubConfigurations(
      String key, AppSecModuleConfigurer.Reconfiguration reconfiguration) {
    maybeInitializeDefaultConfig();
    for (Map.Entry<String, SubconfigListener> entry : subconfigListeners.entrySet()) {
      SubconfigListener listener = entry.getValue();
      try {
        listener.onNewSubconfig(key, reconfiguration);
        reconfiguration.reloadSubscriptions();
      } catch (Exception rte) {
        log.warn("Error updating configuration of app sec module listening on key {}", key, rte);
      }
    }
  }

  private void maybeInitializeDefaultConfig() {
    if (usedDDWafConfigKeys.isEmpty() && !hasUserWafConfig && !defaultConfigActivated) {
      // no config left in the WAF builder, add the default config
      init();
    }
  }

  @Override
  public void init() {
    Map<String, Object> wafConfig;
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
        defaultConfigActivated = true;
      } catch (IOException e) {
        log.error("Error loading default config", e);
        throw new AbortStartupException("Error loading default config", e);
      }
    } else {
      hasUserWafConfig = true;
    }

    if (wafConfig.isEmpty()) {
      throw new IllegalStateException("Expected default waf config to be available");
    }
    try {
      handleWafUpdateResultReport(DEFAULT_WAF_CONFIG_RULE, wafConfig);
    } catch (AppSecModule.AppSecModuleActivationException e) {
      throw new RuntimeException(e);
    }
  }

  public void maybeSubscribeConfigPolling() {
    final ProductActivation appSecActivation = tracerConfig.getAppSecActivation();
    if (appSecActivation == ProductActivation.FULLY_DISABLED) {
      return; // shouldn't happen but just in case.
    }
    if (this.configurationPoller != null) {
      if (hasUserWafConfig && appSecActivation == ProductActivation.FULLY_ENABLED) {
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

  public WafBuilder getWafBuilder() throws AppSecModule.AppSecModuleActivationException {
    if (!WafInitialization.ONLINE) {
      log.debug("In-app WAF initialization failed. See previous log entries");
      throw new AppSecModule.AppSecModuleActivationException(
          "In-app WAF initialization failed. See previous log entries");
    }
    if (this.wafBuilder == null || !this.wafBuilder.isOnline()) {
      this.wafBuilder = new WafBuilder(createWafConfig(Config.get()));
    }
    return this.wafBuilder;
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
    public void addSubConfigListener(String key, SubconfigListener listener) {
      listenerMap.put(key, listener);
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

  private static Map<String, Object> loadDefaultWafConfig() throws IOException {
    log.debug("Loading default waf config");
    try (InputStream is =
        AppSecConfigServiceImpl.class
            .getClassLoader()
            .getResourceAsStream(DEFAULT_CONFIG_LOCATION)) {
      if (is == null) {
        throw new IOException("Resource " + DEFAULT_CONFIG_LOCATION + " not found");
      }

      Map<String, Object> ret = ADAPTER.fromJson(Okio.buffer(Okio.source(is)));

      StandardizedLogging._initialConfigSourceAndLibddwafVersion(log, "<bundled config>");
      if (log.isInfoEnabled()) {
        StandardizedLogging.numLoadedRules(log, "<bundled config>", countRules(ret));
      }
      return ret;
    }
  }

  private static Map<String, Object> loadUserWafConfig(Config tracerConfig) throws IOException {
    log.debug("Loading user waf config");
    String filename = tracerConfig.getAppSecRulesFile();
    if (filename == null) {
      return null;
    }
    try (InputStream is = new FileInputStream(filename)) {
      Map<String, Object> ret = ADAPTER.fromJson(Okio.buffer(Okio.source(is)));

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

  private static int countRules(Map<String, Object> config) {
    return ((List<?>) config.getOrDefault("rules", Collections.emptyList())).size();
  }

  @Override
  public void close() {
    if (this.configurationPoller == null) {
      return;
    }
    this.configurationPoller.removeCapabilities(
        CAPABILITY_ASM_ACTIVATION
            | CAPABILITY_ASM_DD_RULES
            | CAPABILITY_ASM_DD_MULTICONFIG
            | CAPABILITY_ASM_IP_BLOCKING
            | CAPABILITY_ASM_EXCLUSIONS
            | CAPABILITY_ASM_EXCLUSION_DATA
            | CAPABILITY_ASM_REQUEST_BLOCKING
            | CAPABILITY_ASM_USER_BLOCKING
            | CAPABILITY_ASM_CUSTOM_RULES
            | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
            | CAPABILITY_ASM_TRUSTED_IPS
            | CAPABILITY_ASM_PROCESSOR_OVERRIDES
            | CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
            | CAPABILITY_ASM_RASP_SQLI
            | CAPABILITY_ASM_RASP_SSRF
            | CAPABILITY_ASM_RASP_LFI
            | CAPABILITY_ASM_RASP_CMDI
            | CAPABILITY_ASM_RASP_SHI
            | CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE
            | CAPABILITY_ENDPOINT_FINGERPRINT
            | CAPABILITY_ASM_SESSION_FINGERPRINT
            | CAPABILITY_ASM_NETWORK_FINGERPRINT
            | CAPABILITY_ASM_HEADER_FINGERPRINT
            | CAPABILITY_ASM_TRACE_TAGGING_RULES
            | CAPABILITY_ASM_EXTENDED_DATA_COLLECTION);
    this.configurationPoller.removeListeners(Product.ASM_DD);
    this.configurationPoller.removeListeners(Product.ASM_DATA);
    this.configurationPoller.removeListeners(Product.ASM);
    this.configurationPoller.removeListeners(Product.ASM_FEATURES);
    unsubscribeSCA();
    this.configurationPoller.removeConfigurationEndListener(applyRemoteConfigListener);
    this.subscribedToRulesAndData.set(false);
    this.configurationPoller.stop();
    if (this.wafBuilder != null) {
      this.wafBuilder.close();
      this.wafBuilder = null;
    }
  }

  private void applyRemoteConfigListener() {
    // apply ASM_FEATURES configuration first as they might enable AppSec
    final AppSecFeatures features = mergedAsmFeatures.getMergedData();
    setAppSecActivation(features.asm);
    setUserIdCollectionMode(features.autoUserInstrum);

    if (!AppSecSystem.isActive()) {
      return;
    }

    distributeSubConfigurations("waf", reconfiguration);
  }

  private void setAppSecActivation(final AppSecFeatures.Asm asm) {
    final boolean newState;
    if (asm == null) {
      newState = tracerConfig.getAppSecActivation() == ProductActivation.FULLY_ENABLED;
    } else {
      newState = asm.enabled;
      // Report AppSec activation change via telemetry when modified via remote config
      ConfigCollector.get().putRemote(APPSEC_ENABLED, asm.enabled);
    }
    if (AppSecSystem.isActive() != newState) {
      log.info("AppSec {} (runtime)", newState ? "enabled" : "disabled");
      AppSecSystem.setActive(newState);
      updateRulesAndDataSubscription();
    }
  }

  private void setUserIdCollectionMode(final AppSecFeatures.AutoUserInstrum autoUserInstrum) {
    UserIdCollectionMode current = UserIdCollectionMode.get();
    UserIdCollectionMode newMode;
    if (autoUserInstrum == null) {
      newMode = tracerConfig.getAppSecUserIdCollectionMode();
    } else {
      newMode = UserIdCollectionMode.fromRemoteConfig(autoUserInstrum.mode);
    }
    if (newMode != current) {
      log.info("User ID collection mode changed via remote-config: {} -> {}", current, newMode);
    }
  }

  private static WafConfig createWafConfig(Config config) {
    WafConfig wafConfig = new WafConfig();
    String keyRegexp = config.getAppSecObfuscationParameterKeyRegexp();
    if (keyRegexp != null) {
      wafConfig.obfuscatorKeyRegex = keyRegexp;
    } else { // reset
      wafConfig.obfuscatorKeyRegex = WafConfig.DEFAULT_KEY_REGEX;
    }
    String valueRegexp = config.getAppSecObfuscationParameterValueRegexp();
    if (valueRegexp != null) {
      wafConfig.obfuscatorValueRegex = valueRegexp;
    } else { // reset
      wafConfig.obfuscatorValueRegex = WafConfig.DEFAULT_VALUE_REGEX;
    }
    return wafConfig;
  }
}
