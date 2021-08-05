package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.fleet.FleetService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
  private final AtomicReference<Map<String, Object>> lastConfig =
      new AtomicReference<>(Collections.emptyMap());
  private final ConcurrentHashMap<String, SubconfigListener> subconfigListeners =
      new ConcurrentHashMap<>();
  private volatile FleetService.FleetSubscription fleetSubscription;

  public AppSecConfigServiceImpl(FleetService fleetService) {
    try {
      Map<String, Object> config = loadDefaultConfig();
      lastConfig.set(config);
    } catch (IOException e) {
      log.error("Error loading default config", e);
    }
    this.fleetService = fleetService;
  }

  private void subscribeFleetService(FleetService fleetService) {
    this.fleetSubscription =
        fleetService.subscribe(
            FleetService.Product.APPSEC,
            is -> {
              try {
                Map<String, Object> stringObjectMap =
                    ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
                distributeSubConfigurations(stringObjectMap);
                this.lastConfig.set(stringObjectMap);
              } catch (IOException e) {
                log.error("Error deserializing appsec config", e);
              }
            });
  }

  private void distributeSubConfigurations(Map<String, Object> newConfig) {
    for (Map.Entry<String, SubconfigListener> entry : subconfigListeners.entrySet()) {
      String key = entry.getKey();
      if (!newConfig.containsKey(key)) {
        continue;
      }
      SubconfigListener listener = entry.getValue();
      try {
        listener.onNewSubconfig(newConfig.get(key));
      } catch (RuntimeException rte) {
        log.warn("Config listener threw", rte);
      }
    }
  }

  @Override
  public void init() {
    subscribeFleetService(fleetService);
  }

  @Override
  public Optional<Object> addSubConfigListener(String key, SubconfigListener listener) {
    this.subconfigListeners.put(key, listener);
    Map<String, Object> lastConfig = this.lastConfig.get();
    return Optional.ofNullable(lastConfig.get(key));
  }

  private static Map<String, Object> loadDefaultConfig() throws IOException {
    try (InputStream is =
        AppSecConfigServiceImpl.class
            .getClassLoader()
            .getResourceAsStream(DEFAULT_CONFIG_LOCATION)) {
      if (is == null) {
        throw new IOException("Resource " + DEFAULT_CONFIG_LOCATION + " not found");
      }

      return ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
    }
  }

  @Override
  public void close() {
    FleetService.FleetSubscription sub = this.fleetSubscription;
    if (sub != null) {
      sub.cancel();
    }
  }
}
