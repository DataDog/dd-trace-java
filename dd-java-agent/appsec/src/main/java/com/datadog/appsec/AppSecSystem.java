package com.datadog.appsec;

import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.gateway.GatewayBridge;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.InstrumentationGateway;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);
  private static final AtomicBoolean STARTED = new AtomicBoolean();

  public static void start(InstrumentationGateway gw) {
    final Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.info("AppSec has started");
    STARTED.set(true);

    EventDispatcher eventDispatcher = new EventDispatcher();
    GatewayBridge gatewayBridge = new GatewayBridge(gw, eventDispatcher);
    gatewayBridge.init();

    loadModules(eventDispatcher);
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
    }
  }

  public static boolean isStarted() {
    return STARTED.get();
  }
}
