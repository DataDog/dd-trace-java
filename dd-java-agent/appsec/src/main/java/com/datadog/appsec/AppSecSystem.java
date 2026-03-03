package com.datadog.appsec;

import com.datadog.appsec.api.security.ApiSecuritySampler;
import com.datadog.appsec.api.security.ApiSecuritySamplerImpl;
import com.datadog.appsec.api.security.AppSecSpanPostProcessor;
import com.datadog.appsec.blocking.BlockingServiceImpl;
import com.datadog.appsec.config.AppSecConfigService;
import com.datadog.appsec.config.AppSecConfigServiceImpl;
import com.datadog.appsec.ddwaf.WAFModule;
import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.event.ReplaceableEventProducerService;
import com.datadog.appsec.gateway.GatewayBridge;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.appsec.util.StandardizedLogging;
import datadog.appsec.api.blocking.Blocking;
import datadog.appsec.api.blocking.BlockingService;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.telemetry.ProductChange;
import datadog.trace.api.telemetry.ProductChangeCollector;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);
  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static final Map<AppSecModule, String> STARTED_MODULES_INFO = new HashMap<>();
  private static AppSecConfigServiceImpl APP_SEC_CONFIG_SERVICE;
  private static ReplaceableEventProducerService REPLACEABLE_EVENT_PRODUCER; // testing
  private static Runnable STOP_SUBSCRIPTION_SERVICE;
  private static Runnable RESET_SUBSCRIPTION_SERVICE;
  private static final AtomicBoolean API_SECURITY_INITIALIZED = new AtomicBoolean(false);
  private static volatile ApiSecuritySampler API_SECURITY_SAMPLER = new ApiSecuritySampler.NoOp();

  public static void start(
      java.lang.instrument.Instrumentation inst,
      SubscriptionService gw,
      SharedCommunicationObjects sco) {
    try {
      doStart(inst, gw, sco);
    } catch (AbortStartupException ase) {
      throw ase;
    } catch (RuntimeException | Error e) {
      StandardizedLogging.appSecStartupError(log, e);
      setActive(false);
      throw new AbortStartupException(e);
    }
  }

  private static void doStart(
      java.lang.instrument.Instrumentation inst,
      SubscriptionService gw,
      SharedCommunicationObjects sco) {
    final Config config = Config.get();
    ProductActivation appSecEnabledConfig = config.getAppSecActivation();
    if (appSecEnabledConfig == ProductActivation.FULLY_DISABLED) {
      log.debug("AppSec: disabled");
      return;
    }
    log.debug("AppSec is starting ({})", appSecEnabledConfig);

    REPLACEABLE_EVENT_PRODUCER = new ReplaceableEventProducerService();
    EventDispatcher eventDispatcher = new EventDispatcher();
    REPLACEABLE_EVENT_PRODUCER.replaceEventProducerService(eventDispatcher);

    ConfigurationPoller configurationPoller = sco.configurationPoller(config);
    // may throw and abort startup
    APP_SEC_CONFIG_SERVICE =
        new AppSecConfigServiceImpl(
            config, configurationPoller, () -> reloadSubscriptions(REPLACEABLE_EVENT_PRODUCER));
    if (appSecEnabledConfig == ProductActivation.FULLY_ENABLED) {
      APP_SEC_CONFIG_SERVICE.init();
    }
    sco.createRemaining(config);

    GatewayBridge gatewayBridge =
        new GatewayBridge(
            gw,
            REPLACEABLE_EVENT_PRODUCER,
            () -> API_SECURITY_SAMPLER,
            APP_SEC_CONFIG_SERVICE.getTraceSegmentPostProcessors());

    loadModules(
        eventDispatcher, sco.monitoring, appSecEnabledConfig == ProductActivation.FULLY_ENABLED);

    gatewayBridge.init();
    STOP_SUBSCRIPTION_SERVICE = gatewayBridge::stop;
    RESET_SUBSCRIPTION_SERVICE = gatewayBridge::reset;

    setActive(appSecEnabledConfig == ProductActivation.FULLY_ENABLED);

    // Initialize SCA instrumentation before subscribing to Remote Config
    APP_SEC_CONFIG_SERVICE.setInstrumentation(inst);

    APP_SEC_CONFIG_SERVICE.maybeSubscribeConfigPolling();

    Blocking.setBlockingService(new BlockingServiceImpl(REPLACEABLE_EVENT_PRODUCER));

    STARTED.set(true);

    String startedAppSecModules = String.join(", ", STARTED_MODULES_INFO.values());
    if (appSecEnabledConfig == ProductActivation.FULLY_ENABLED) {
      log.info("AppSec is {} with {}", appSecEnabledConfig, startedAppSecModules);
    } else {
      log.debug("AppSec is {} with {}", appSecEnabledConfig, startedAppSecModules);
    }
  }

  public static boolean isActive() {
    return ActiveSubsystems.APPSEC_ACTIVE;
  }

  public static void setActive(boolean status) {
    ActiveSubsystems.APPSEC_ACTIVE = status;
    // Report to the product change via telemetry
    log.debug("AppSec is now {}", status ? "active" : "inactive");
    ProductChangeCollector.get()
        .update(new ProductChange().productType(ProductChange.ProductType.APPSEC).enabled(status));
    if (status) {
      maybeInitializeApiSecurity();
    }
  }

  public static void stop() {
    if (!STARTED.getAndSet(false)) {
      return;
    }
    REPLACEABLE_EVENT_PRODUCER = null;
    final Runnable stop = STOP_SUBSCRIPTION_SERVICE;
    if (stop != null) {
      stop.run();
      STOP_SUBSCRIPTION_SERVICE = null;
      RESET_SUBSCRIPTION_SERVICE = null;
    }
    Blocking.setBlockingService(BlockingService.NOOP);
    APP_SEC_CONFIG_SERVICE.close();
  }

  private static void loadModules(
      EventDispatcher eventDispatcher, Monitoring monitoring, boolean appSecEnabledConfig) {
    EventDispatcher.DataSubscriptionSet dataSubscriptionSet =
        new EventDispatcher.DataSubscriptionSet();

    final List<AppSecModule> modules = Collections.singletonList(new WAFModule(monitoring));
    APP_SEC_CONFIG_SERVICE.modulesToUpdateVersionIn(modules);
    for (AppSecModule module : modules) {
      log.debug("Starting appsec module {}", module.getName());
      try {
        AppSecConfigService.TransactionalAppSecModuleConfigurer cfgObject =
            APP_SEC_CONFIG_SERVICE.createAppSecModuleConfigurer();
        module.setRuleVersion(APP_SEC_CONFIG_SERVICE.getCurrentRuleVersion());
        if (appSecEnabledConfig) {
          module.setWafBuilder(APP_SEC_CONFIG_SERVICE.getWafBuilder());
        }
        module.config(cfgObject);
        cfgObject.commit();
      } catch (RuntimeException | AppSecModule.AppSecModuleActivationException t) {
        log.error("Startup of appsec module {} failed", module.getName(), t);
        continue;
      }

      for (AppSecModule.DataSubscription sub : module.getDataSubscriptions()) {
        dataSubscriptionSet.addSubscription(sub.getSubscribedAddresses(), sub);
      }

      STARTED_MODULES_INFO.put(module, module.getInfo());
    }

    eventDispatcher.subscribeDataAvailable(dataSubscriptionSet);
  }

  private static void reloadSubscriptions(
      ReplaceableEventProducerService replaceableEventProducerService) {
    EventDispatcher.DataSubscriptionSet dataSubscriptionSet =
        new EventDispatcher.DataSubscriptionSet();

    EventDispatcher newEd = new EventDispatcher();
    for (AppSecModule module : STARTED_MODULES_INFO.keySet()) {
      module.setRuleVersion(APP_SEC_CONFIG_SERVICE.getCurrentRuleVersion());
      for (AppSecModule.DataSubscription sub : module.getDataSubscriptions()) {
        dataSubscriptionSet.addSubscription(sub.getSubscribedAddresses(), sub);
      }
    }

    newEd.subscribeDataAvailable(dataSubscriptionSet);

    replaceableEventProducerService.replaceEventProducerService(newEd);

    final Runnable reset = RESET_SUBSCRIPTION_SERVICE;
    if (reset != null) {
      reset.run();
    }
  }

  private static void maybeInitializeApiSecurity() {
    if (!Config.get().isApiSecurityEnabled()) {
      return;
    }
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      return;
    }
    // We initialize API Security the first time AppSec becomes active.
    // We never de-initialize it, as that could lead to a leak of open WAF contexts in-flight.
    if (API_SECURITY_INITIALIZED.compareAndSet(false, true)) {
      if (SpanPostProcessor.Holder.INSTANCE == SpanPostProcessor.Holder.NOOP) {
        ApiSecuritySampler requestSampler = new ApiSecuritySamplerImpl();
        SpanPostProcessor.Holder.INSTANCE =
            new AppSecSpanPostProcessor(requestSampler, REPLACEABLE_EVENT_PRODUCER);
        API_SECURITY_SAMPLER = requestSampler;
      }
    }
  }

  public static boolean isStarted() {
    return STARTED.get();
  }

  public static Set<String> getStartedModulesInfo() {
    if (isStarted()) {
      return STARTED_MODULES_INFO.keySet().stream()
          .map(AppSecModule::getName)
          .collect(Collectors.toSet());
    } else {
      return Collections.emptySet();
    }
  }
}
