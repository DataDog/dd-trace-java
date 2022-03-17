package com.datadog.appsec.config;

import static com.datadog.appsec.util.StandardizedLogging.RulesInvalidReason.INVALID_JSON_FILE;

import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.StandardizedLogging;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.fleet.FleetService;
import datadog.trace.api.Config;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecConfigServiceImpl implements AppSecConfigService {

  private static final Logger log = LoggerFactory.getLogger(AppSecConfigServiceImpl.class);

  private static final String DEFAULT_CONFIG_LOCATION = "default_config.json";
  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private final FleetService fleetService;
  // for new subconfig subscribers
  private final AtomicReference<Map<String, AppSecConfig>> lastConfig =
      new AtomicReference<>(Collections.emptyMap());
  private final ConcurrentHashMap<String, SubconfigListener> subconfigListeners =
      new ConcurrentHashMap<>();
  private final Config tracerConfig;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors = new ArrayList<>();
  private volatile FleetService.FleetSubscription fleetSubscription;

  public AppSecConfigServiceImpl(Config tracerConfig, FleetService fleetService) {
    this.tracerConfig = tracerConfig;
    this.fleetService = fleetService;
  }

  private void subscribeFleetService(FleetService fleetService) {
    this.fleetSubscription =
        fleetService.subscribe(
            FleetService.Product.APPSEC,
            is -> {
              try {
                Map<String, AppSecConfig> stringObjectMap =
                    deserializeConfig(Okio.buffer(Okio.source(is)));
                distributeSubConfigurations(stringObjectMap);
                this.lastConfig.set(stringObjectMap);
              } catch (IOException e) {
                log.error("Error deserializing appsec config", e);
              }
            });
  }

  private void distributeSubConfigurations(Map<String, AppSecConfig> newConfig) {
    for (Map.Entry<String, SubconfigListener> entry : subconfigListeners.entrySet()) {
      String key = entry.getKey();
      if (!newConfig.containsKey(key)) {
        continue;
      }
      SubconfigListener listener = entry.getValue();
      try {
        listener.onNewSubconfig(newConfig.get(key));
      } catch (Exception rte) {
        log.warn("Error updating configuration of app sec module listening on key " + key, rte);
      }
    }
  }

  @Override
  public void init(boolean initFleetService) {
    Map<String, AppSecConfig> config;
    try {
      config = loadUserConfig(tracerConfig);
    } catch (Exception e) {
      log.error("Error loading user-provided config", e);
      throw new AbortStartupException("Error loading user-provided config", e);
    }
    if (config == null) {
      try {
        config = loadDefaultConfig();
      } catch (IOException e) {
        log.error("Error loading default config", e);
        throw new AbortStartupException("Error loading default config", e);
      }
    }
    lastConfig.set(config);
    if (initFleetService) {
      subscribeFleetService(fleetService);
    }
  }

  @Override
  public Optional<AppSecConfig> addSubConfigListener(String key, SubconfigListener listener) {
    this.subconfigListeners.put(key, listener);
    Map<String, AppSecConfig> lastConfig = this.lastConfig.get();
    return Optional.ofNullable(lastConfig.get(key));
  }

  @Override
  public void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor) {
    this.traceSegmentPostProcessors.add(interceptor);
  }

  public List<TraceSegmentPostProcessor> getTraceSegmentPostProcessors() {
    return traceSegmentPostProcessors;
  }

  // public for testing only
  public static Map<String, AppSecConfig> deserializeConfig(BufferedSource src) throws IOException {
    Map<String, Object> rawConfig = ADAPTER.fromJson(src);
    if (rawConfig == null) {
      throw new IOException("Unable deserialize Json config");
    }

    if (rawConfig.containsKey("version")) {
      // in case if we have single config simulate multi-config structure
      rawConfig = Collections.singletonMap("waf", rawConfig);
    }

    Map<String, AppSecConfig> ret = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : rawConfig.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (!(value instanceof Map)) {
        throw new IOException("Expect config to be a map");
      }
      ret.put(key, AppSecConfig.valueOf((Map<String, Object>) value));
    }
    return ret;
  }

  private static Map<String, AppSecConfig> loadDefaultConfig() throws IOException {
    try (InputStream is =
        AppSecConfigServiceImpl.class
            .getClassLoader()
            .getResourceAsStream(DEFAULT_CONFIG_LOCATION)) {
      if (is == null) {
        throw new IOException("Resource " + DEFAULT_CONFIG_LOCATION + " not found");
      }

      Map<String, AppSecConfig> ret = deserializeConfig(Okio.buffer(Okio.source(is)));

      StandardizedLogging._initialConfigSourceAndLibddwafVersion(log, "<bundled config>");
      if (log.isInfoEnabled()) {
        StandardizedLogging.numLoadedRules(log, "<bundled config>", countRules(ret));
      }

      return ret;
    }
  }

  private static Map<String, AppSecConfig> loadUserConfig(Config tracerConfig) throws IOException {
    String filename = tracerConfig.getAppSecRulesFile();
    if (filename == null) {
      return null;
    }
    try (InputStream is = new FileInputStream(filename)) {
      Map<String, AppSecConfig> ret = deserializeConfig(Okio.buffer(Okio.source(is)));

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

  /** Provide total amount of all events from all configs */
  private static int countRules(Map<String, AppSecConfig> config) {
    // get sum for each config->AppSecConfig.getEvents().size()
    return config.values().stream().map(AppSecConfig::getRules).mapToInt(List::size).sum();
  }

  @Override
  public void close() {
    FleetService.FleetSubscription sub = this.fleetSubscription;
    if (sub != null) {
      sub.cancel();
    }
  }
}
