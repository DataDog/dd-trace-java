package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.fleet.FleetService;
import datadog.communication.fleet.FleetServiceImpl;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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

  // for new subconfig subscribers
  private final AtomicReference<Map<String, Object>> lastConfig =
      new AtomicReference<>(Collections.emptyMap());
  private final ConcurrentHashMap<String, SubconfigListener> subconfigListeners =
      new ConcurrentHashMap<>();
  private final Thread thread;

  // for testing
  volatile CountDownLatch testingLatch;

  public AppSecConfigServiceImpl(FleetService fleetService) {
    this.thread = new Thread(new SubscribeFleetServiceRunnable(fleetService), "appsec_config");
    this.thread.setDaemon(true);

    try {
      Map<String, Object> config = loadDefaultConfig();
      lastConfig.set(config);
    } catch (IOException e) {
      log.error("Error loading default config", e);
    }
  }

  @Override
  public void init() {
    this.thread.start();
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
  public Optional<Object> addSubConfigListener(String key, SubconfigListener listener) {
    this.subconfigListeners.put(key, listener);
    Map<String, Object> lastConfig = this.lastConfig.get();
    return Optional.ofNullable(lastConfig.get(key));
  }

  @Override
  public void close() {
    this.thread.interrupt();
  }

  private static final Map<String, Object> ERROR_SENTINEL = new HashMap<>();
  private static final Map<String, Object> COMPLETION_SENTINEL = new HashMap<>();

  private class SubscribeFleetServiceRunnable implements Runnable {
    private static final double BACKOFF_INITIAL = 3.0d;
    private static final double BACKOFF_BASE = 3.0d;
    private static final double BACKOFF_MAX_EXPONENT = 3.0d;

    private final FleetService fleetService;
    private final BlockingQueue<Map<String, Object>> configsQueue = new ArrayBlockingQueue<>(10);
    private int consecutiveFailures;

    private SubscribeFleetServiceRunnable(FleetService fleetService) {
      this.fleetService = fleetService;
    }

    @Override
    public void run() {
      subscribeFleetService(fleetService);
      if (testingLatch != null) {
        testingLatch.countDown();
      }
      while (!Thread.interrupted()) {
        try {
          mainLoopIteration();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          if (testingLatch != null) {
            testingLatch.countDown();
          }
        }
      }
    }

    private void mainLoopIteration() throws InterruptedException {
      Map<String, Object> newConfig = configsQueue.take();
      if (newConfig == ERROR_SENTINEL) {
        failureWait();
        if (!Thread.currentThread().isInterrupted()) {
          subscribeFleetService(fleetService);
        }
      } else if (newConfig == COMPLETION_SENTINEL) {
        Thread.currentThread().interrupt();
      } else {
        log.debug("New AppSec config: {}", newConfig);
        consecutiveFailures = 0;
        distributeSubConfigurations(newConfig);
      }
    }

    private void subscribeFleetService(FleetService fleetService) {
      fleetService.subscribe(
          FleetService.Product.APPSEC,
          new FleetServiceImpl.ConfigurationListener() {
            @Override
            public void onNewConfiguration(InputStream is) {
              try {
                Map<String, Object> stringObjectMap =
                    ADAPTER.fromJson(Okio.buffer(Okio.source(is)));
                boolean accepted = configsQueue.offer(stringObjectMap);
                if (!accepted) {
                  log.warn("New appsec configured could not be propagated: full queue");
                }
              } catch (IOException e) {
                log.error("Error deserializing appsec config", e);
                onError(e);
              }
            }

            @Override
            public void onError(Throwable t) {
              log.info(
                  "FleetService indicated error; will try resubscribing to appsec config changes");
              try {
                configsQueue.clear();
                configsQueue.put(ERROR_SENTINEL);
              } catch (InterruptedException e) {
                log.info("Could not add error sentinel to the queue");
              }
            }

            @Override
            public void onCompleted() {
              log.info("onCompleted called; will not resubscribe for config changes");
              configsQueue.clear();
              try {
                configsQueue.put(COMPLETION_SENTINEL);
              } catch (InterruptedException e) {
                log.info("Could not add completion sentinel to the queue");
              }
            }
          });
    }

    private void failureWait() {
      double waitSeconds;
      consecutiveFailures++;
      waitSeconds =
          BACKOFF_INITIAL
              * Math.pow(
                  BACKOFF_BASE, Math.min((double) consecutiveFailures - 1, BACKOFF_MAX_EXPONENT));
      if (testingLatch != null) {
        waitSeconds = 0;
      }
      log.warn(
          "Last subscription attempt failed; " + "will retry in {} seconds (num failures: {})",
          waitSeconds,
          consecutiveFailures);
      try {
        Thread.sleep((long) (waitSeconds * 1000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
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
}
