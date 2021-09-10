package com.datadog.appsec;

import com.datadog.appsec.config.AppSecConfigService;
import com.datadog.appsec.config.AppSecConfigServiceImpl;
import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.gateway.GatewayBridge;
import com.datadog.appsec.report.AppSecApi;
import com.datadog.appsec.report.ReportService;
import com.datadog.appsec.report.ReportServiceImpl;
import com.datadog.appsec.report.ReportStrategy;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.JvmTime;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.fleet.FleetService;
import datadog.communication.fleet.FleetServiceImpl;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory;
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
  private static AppSecConfigService APP_SEC_CONFIG_SERVICE;
  private static ReportService REPORT_SERVICE;

  public static void start(SubscriptionService gw, SharedCommunicationObjects sco) {
    try {
      doStart(gw, sco);
    } catch (AbortStartupException ase) {
      throw ase;
    } catch (RuntimeException | Error e) {
      StandardizedLogging.appSecStartupError(log, e);
      throw new AbortStartupException(e);
    }
  }

  private static void doStart(SubscriptionService gw, SharedCommunicationObjects sco) {
    final Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.info("AppSec has started");

    //  TODO: FleetService should be shared with other components
    FleetService fleetService =
        new FleetServiceImpl(
            sco, new AgentThreadFactory(AgentThreadFactory.AgentThread.FLEET_MANAGEMENT_POLLER));
    // do not start its thread, support not merged in agent yet
    //    fleetService.init();
    // may throw and abort starup
    APP_SEC_CONFIG_SERVICE = new AppSecConfigServiceImpl(config, fleetService);
    // no point initializing fleet service, as it will receive no notifications
    APP_SEC_CONFIG_SERVICE.init(false);

    EventDispatcher eventDispatcher = new EventDispatcher();
    sco.createRemaining(config);
    AgentTaskScheduler taskScheduler =
        new AgentTaskScheduler(AgentThreadFactory.AgentThread.APPSEC_HTTP_DISPATCHER);
    AppSecApi api = new AppSecApi(sco.monitoring, sco.agentUrl, sco.okHttpClient, taskScheduler);
    REPORT_SERVICE =
        new ReportServiceImpl(
            api,
            new ReportStrategy.Default(JvmTime.Default.INSTANCE),
            ReportServiceImpl.TaskScheduler.of(taskScheduler));
    GatewayBridge gatewayBridge = new GatewayBridge(gw, eventDispatcher, REPORT_SERVICE);

    loadModules(eventDispatcher);
    gatewayBridge.init();

    STARTED.set(true);
  }

  public static void stop() {
    if (!STARTED.getAndSet(false)) {
      return;
    }

    REPORT_SERVICE.close();
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
      log.info("Starting appsec module {}", module.getName());
      try {
        module.config(APP_SEC_CONFIG_SERVICE);
      } catch (RuntimeException | AppSecModule.AppSecModuleActivationException t) {
        log.error("Startup of appsec module {} failed", module.getName(), t);
        continue;
      }

      for (AppSecModule.EventSubscription sub : module.getEventSubscriptions()) {
        eventSubscriptionSet.addSubscription(sub.eventType, sub);
      }

      for (AppSecModule.DataSubscription sub : module.getDataSubscriptions()) {
        dataSubscriptionSet.addSubscription(sub.getSubscribedAddresses(), sub);
      }

      STARTED_MODULE_NAMES.add(module.getName());
    }

    eventDispatcher.subscribeEvents(eventSubscriptionSet);
    eventDispatcher.subscribeDataAvailable(dataSubscriptionSet);
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
