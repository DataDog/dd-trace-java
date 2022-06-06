package com.datadog.appsec;

import com.datadog.appsec.config.AppSecConfigServiceImpl;
import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.gateway.GatewayBridge;
import com.datadog.appsec.gateway.RateLimiter;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.fleet.FleetService;
import datadog.communication.fleet.FleetServiceImpl;
import datadog.communication.monitor.Counter;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.Strings;
import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);
  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static final Map<String, String> STARTED_MODULES_INFO = new HashMap<>();
  private static AppSecConfigServiceImpl APP_SEC_CONFIG_SERVICE;

  public static void start(
      Instrumentation instrumentation, SubscriptionService gw, SharedCommunicationObjects sco) {
    try {
      doStart(instrumentation, gw, sco);
    } catch (AbortStartupException ase) {
      throw ase;
    } catch (RuntimeException | Error e) {
      StandardizedLogging.appSecStartupError(log, e);
      throw new AbortStartupException(e);
    }
  }

  private static void doStart(
      Instrumentation instrumentation, SubscriptionService gw, SharedCommunicationObjects sco) {
    final Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.debug("AppSec is starting");

    //  TODO: FleetService should be shared with other components
    FleetService fleetService =
        new FleetServiceImpl(
            sco, new AgentThreadFactory(AgentThreadFactory.AgentThread.FLEET_MANAGEMENT_POLLER));
    // do not start its thread, support not merged in agent yet
    //    fleetService.init();
    // may throw and abort startup
    APP_SEC_CONFIG_SERVICE = new AppSecConfigServiceImpl(config, fleetService);
    // no point initializing fleet service, as it will receive no notifications
    APP_SEC_CONFIG_SERVICE.init(false);

    sco.createRemaining(config);

    EventDispatcher eventDispatcher = new EventDispatcher();
    RateLimiter rateLimiter = getRateLimiter(config, sco.monitoring);
    GatewayBridge gatewayBridge =
        new GatewayBridge(
            gw,
            eventDispatcher,
            rateLimiter,
            config.getAppSecIpAddrHeader(),
            APP_SEC_CONFIG_SERVICE.getTraceSegmentPostProcessors());

    loadModules(eventDispatcher);
    gatewayBridge.init();

    STARTED.set(true);

    String startedAppSecModules = Strings.join(", ", STARTED_MODULES_INFO.values());
    log.info("AppSec has started with {}", startedAppSecModules);
  }

  private static RateLimiter getRateLimiter(Config config, Monitoring monitoring) {
    RateLimiter rateLimiter = null;
    int appSecTraceRateLimit = config.getAppSecTraceRateLimit();
    if (appSecTraceRateLimit > 0) {
      Counter counter = monitoring.newCounter("_dd.java.appsec.rate_limit.dropped_traces");
      rateLimiter =
          new RateLimiter(
              appSecTraceRateLimit, SystemTimeSource.INSTANCE, () -> counter.increment(1));
    }
    return rateLimiter;
  }

  public static void stop() {
    if (!STARTED.getAndSet(false)) {
      return;
    }

    APP_SEC_CONFIG_SERVICE.close();
  }

  private static void loadModules(EventDispatcher eventDispatcher) {
    EventDispatcher.EventSubscriptionSet eventSubscriptionSet =
        new EventDispatcher.EventSubscriptionSet();
    EventDispatcher.DataSubscriptionSet dataSubscriptionSet =
        new EventDispatcher.DataSubscriptionSet();

    ServiceLoader<AppSecModule> modules =
        ServiceLoader.load(AppSecModule.class, AppSecSystem.class.getClassLoader());
    for (AppSecModule module : modules) {
      log.debug("Starting appsec module {}", module.getName());
      try {
        module.config(APP_SEC_CONFIG_SERVICE);
      } catch (RuntimeException | AppSecModule.AppSecModuleActivationException t) {
        log.error("Startup of appsec module {} failed", module.getName(), t);
        continue;
      }

      // TODO: the set needs to be updated upon runtime module reconfiguration (when supported)
      //       (and the subscription caches invalidated)
      for (AppSecModule.EventSubscription sub : module.getEventSubscriptions()) {
        eventSubscriptionSet.addSubscription(sub.eventType, sub);
      }

      for (AppSecModule.DataSubscription sub : module.getDataSubscriptions()) {
        dataSubscriptionSet.addSubscription(sub.getSubscribedAddresses(), sub);
      }

      STARTED_MODULES_INFO.put(module.getName(), module.getInfo());
    }

    eventDispatcher.subscribeEvents(eventSubscriptionSet);
    eventDispatcher.subscribeDataAvailable(dataSubscriptionSet);
  }

  public static boolean isStarted() {
    return STARTED.get();
  }

  public static Set<String> getStartedModulesInfo() {
    if (isStarted()) {
      return Collections.unmodifiableSet(STARTED_MODULES_INFO.keySet());
    } else {
      return Collections.emptySet();
    }
  }
}
