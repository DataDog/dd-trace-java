package com.datadog.appsec;

import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.gateway.GatewayBridge;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.SubscriptionService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);
  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static final List<String> STARTED_MODULE_NAMES = new ArrayList<>();

  public static void start(SubscriptionService gw) {
    final Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.info("AppSec has started");

    EventDispatcher eventDispatcher = new EventDispatcher();
    GatewayBridge gatewayBridge = new GatewayBridge(gw, eventDispatcher);
    gatewayBridge.init();

    loadModules(eventDispatcher);

    STARTED.set(true);
  }

  private static void loadModules(EventDispatcher eventDispatcher) {
    ServiceLoader<AppSecModule> modules = ServiceLoader.load(AppSecModule.class);
    for (AppSecModule module : modules) {
      log.info("Starting appsec module {}", module.getName());
      for (AppSecModule.EventSubscription sub : module.getEventSubscriptions()) {
        eventDispatcher.subscribeEvent(sub.eventType, sub);
      }

      for (AppSecModule.DataSubscription sub : module.getDataSubscriptions()) {
        eventDispatcher.subscribeDataAvailable(sub.getSubscribedAddresses(), sub);
      }

      STARTED_MODULE_NAMES.add(module.getName());
    }
  }

  public static boolean isStarted() {
    return STARTED.get();
  }

  public static List<String> getStartedModuleNames() {
    if (isStarted()) {
      return Collections.unmodifiableList(STARTED_MODULE_NAMES);
    } else {
      return Collections.emptyList();
    }
  }
}
