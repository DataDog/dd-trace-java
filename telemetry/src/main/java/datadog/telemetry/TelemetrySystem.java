package datadog.telemetry;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.telemetry.TelemetryRunnable.TelemetryPeriodicAction;
import datadog.telemetry.dependency.DependencyPeriodicAction;
import datadog.telemetry.dependency.DependencyService;
import datadog.telemetry.dependency.DependencyServiceImpl;
import datadog.telemetry.iast.IastTelemetryPeriodicAction;
import datadog.telemetry.integration.IntegrationPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.util.AgentThreadFactory;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetrySystem {

  private static final long TELEMETRY_STOP_WAIT_MILLIS = 5000L;
  private static final Logger log = LoggerFactory.getLogger(TelemetrySystem.class);

  private static Thread TELEMETRY_THREAD;
  private static DependencyService DEPENDENCY_SERVICE;

  static DependencyService createDependencyService(Instrumentation instrumentation) {
    if (instrumentation != null && Config.get().isTelemetryDependencyServiceEnabled()) {
      DependencyServiceImpl dependencyService = new DependencyServiceImpl();
      dependencyService.installOn(instrumentation);
      dependencyService.schedulePeriodicResolution();
      return dependencyService;
    }
    return null;
  }

  static Thread createTelemetryRunnable(
      TelemetryService telemetryService,
      OkHttpClient okHttpClient,
      DependencyService dependencyService) {
    DEPENDENCY_SERVICE = dependencyService;

    List<TelemetryPeriodicAction> actions = new ArrayList<>();
    actions.add(new IntegrationPeriodicAction());
    if (Verbosity.OFF != Config.get().getIastTelemetryVerbosity()) {
      actions.add(new IastTelemetryPeriodicAction());
    }
    if (null != dependencyService) {
      actions.add(new DependencyPeriodicAction(dependencyService));
    }

    TelemetryRunnable telemetryRunnable =
        new TelemetryRunnable(okHttpClient, telemetryService, actions);
    return AgentThreadFactory.newAgentThread(
        AgentThreadFactory.AgentThread.TELEMETRY, telemetryRunnable);
  }

  public static void startTelemetry(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {
    DependencyService dependencyService = createDependencyService(instrumentation);
    RequestBuilder requestBuilder = new RequestBuilder(sco.agentUrl);
    TelemetryService telemetryService =
        new TelemetryServiceImpl(
            new RequestBuilderSupplier(sco.agentUrl),
            SystemTimeSource.INSTANCE,
            Config.get().getTelemetryHeartbeatInterval());
    TELEMETRY_THREAD =
        createTelemetryRunnable(telemetryService, sco.okHttpClient, dependencyService);
    TELEMETRY_THREAD.start();
  }

  public static void stop() {
    if (DEPENDENCY_SERVICE != null) {
      DEPENDENCY_SERVICE.stop();
    }
    if (TELEMETRY_THREAD != null) {
      TELEMETRY_THREAD.interrupt();
      try {
        TELEMETRY_THREAD.join(TELEMETRY_STOP_WAIT_MILLIS);
      } catch (InterruptedException e) {
        log.warn("Telemetry thread join was interrupted");
      }
      if (TELEMETRY_THREAD.isAlive()) {
        log.warn("Telemetry thread join was not completed");
      }
      TELEMETRY_THREAD = null;
    }
  }
}
