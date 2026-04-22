package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.telemetry.TelemetryRunnable.TelemetryPeriodicAction;
import datadog.telemetry.dependency.DependencyPeriodicAction;
import datadog.telemetry.dependency.DependencyService;
import datadog.telemetry.endpoint.EndpointPeriodicAction;
import datadog.telemetry.integration.IntegrationPeriodicAction;
import datadog.telemetry.log.LogPeriodicAction;
import datadog.telemetry.metric.CiVisibilityMetricPeriodicAction;
import datadog.telemetry.metric.ConfigInversionMetricPeriodicAction;
import datadog.telemetry.metric.CoreMetricsPeriodicAction;
import datadog.telemetry.metric.IastMetricPeriodicAction;
import datadog.telemetry.metric.LLMObsMetricPeriodicAction;
import datadog.telemetry.metric.OtelEnvMetricPeriodicAction;
import datadog.telemetry.metric.WafMetricPeriodicAction;
import datadog.telemetry.products.ProductChangeAction;
import datadog.telemetry.rum.RumPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.util.AgentThreadFactory;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetrySystem {

  private static final long TELEMETRY_STOP_WAIT_MILLIS = 5000L;
  private static final Logger log = LoggerFactory.getLogger(TelemetrySystem.class);

  private static volatile Thread TELEMETRY_THREAD;
  private static volatile DependencyService DEPENDENCY_SERVICE;

  static DependencyService createDependencyService(Instrumentation instrumentation) {
    if (instrumentation != null && Config.get().isTelemetryDependencyServiceEnabled()) {
      DependencyService dependencyService = new DependencyService();
      dependencyService.installOn(instrumentation);
      dependencyService.schedulePeriodicResolution();
      return dependencyService;
    }
    return null;
  }

  static Thread createTelemetryRunnable(
      TelemetryService telemetryService,
      DependencyService dependencyService,
      boolean telemetryMetricsEnabled) {
    DEPENDENCY_SERVICE = dependencyService;
    List<TelemetryPeriodicAction> actions = new ArrayList<>();
    if (telemetryMetricsEnabled) {
      actions.add(new CoreMetricsPeriodicAction());
      actions.add(new OtelEnvMetricPeriodicAction());
      actions.add(new ConfigInversionMetricPeriodicAction());
      actions.add(new IntegrationPeriodicAction());
      actions.add(new WafMetricPeriodicAction());
      if (Verbosity.OFF != Config.get().getIastTelemetryVerbosity()) {
        actions.add(new IastMetricPeriodicAction());
      }
      if (Config.get().isCiVisibilityEnabled() && Config.get().isCiVisibilityTelemetryEnabled()) {
        actions.add(new CiVisibilityMetricPeriodicAction());
      }
      if (Config.get().isLlmObsEnabled()) {
        actions.add(new LLMObsMetricPeriodicAction());
      }
    }
    if (null != dependencyService) {
      actions.add(new DependencyPeriodicAction(dependencyService));
    }
    if (Config.get().isTelemetryLogCollectionEnabled()) {
      actions.add(new LogPeriodicAction());
      log.debug("Telemetry log collection enabled");
    }
    if (InstrumenterConfig.get().isRumEnabled()) {
      RumInjector.enableTelemetry();
      actions.add(new RumPeriodicAction(RumInjector.getTelemetryCollector()));
    }
    actions.add(new ProductChangeAction());
    if (Config.get().isApiSecurityEndpointCollectionEnabled()) {
      actions.add(new EndpointPeriodicAction());
    }

    TelemetryRunnable telemetryRunnable = new TelemetryRunnable(telemetryService, actions);
    return AgentThreadFactory.newAgentThread(
        AgentThreadFactory.AgentThread.TELEMETRY, telemetryRunnable);
  }

  /** Called by reflection (see Agent.startTelemetry) */
  public static void startTelemetry(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {
    Config config = Config.get();
    sco.createRemaining(config);
    DependencyService dependencyService = createDependencyService(instrumentation);
    boolean debug = config.isTelemetryDebugRequestsEnabled();
    DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery = sco.featuresDiscovery(config);

    HttpRetryPolicy.Factory httpRetryPolicy =
        config.isCiVisibilityEnabled()
            ? new HttpRetryPolicy.Factory(2, 100, 2.0, true)
            : HttpRetryPolicy.Factory.NEVER_RETRY;

    TelemetryClient agentClient =
        TelemetryClient.buildAgentClient(sco.agentHttpClient, sco.agentUrl, httpRetryPolicy);
    TelemetryClient intakeClient = TelemetryClient.buildIntakeClient(config, httpRetryPolicy);

    boolean useIntakeClientByDefault =
        config.isCiVisibilityEnabled() && config.isCiVisibilityAgentlessEnabled();
    TelemetryService telemetryService =
        TelemetryService.build(
            ddAgentFeaturesDiscovery, agentClient, intakeClient, useIntakeClientByDefault, debug);

    boolean telemetryMetricsEnabled = config.isTelemetryMetricsEnabled();
    TELEMETRY_THREAD =
        createTelemetryRunnable(telemetryService, dependencyService, telemetryMetricsEnabled);
    TELEMETRY_THREAD.start();
  }

  /** Called by reflection (see Agent.stopTelemetry) */
  public static void stop() {
    DependencyService dependencyService = DEPENDENCY_SERVICE;
    if (dependencyService != null) {
      dependencyService.stop();
    }

    Thread telemetryThread = TELEMETRY_THREAD;
    if (telemetryThread != null) {
      telemetryThread.interrupt();
      try {
        telemetryThread.join(TELEMETRY_STOP_WAIT_MILLIS);
      } catch (InterruptedException e) {
        log.warn("Telemetry thread join was interrupted");
      }
      if (telemetryThread.isAlive()) {
        log.warn("Telemetry thread join was not completed");
      }
    }
  }
}
