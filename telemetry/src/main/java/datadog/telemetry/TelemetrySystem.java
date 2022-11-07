package datadog.telemetry;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.telemetry.api.AppDependenciesLoaded;
import datadog.telemetry.api.AppIntegrationsChange;
import datadog.telemetry.api.AppStarted;
import datadog.telemetry.api.Payload;
import datadog.telemetry.api.Telemetry;
import datadog.telemetry.dependency.DependencyPeriodicAction;
import datadog.telemetry.dependency.DependencyService;
import datadog.telemetry.dependency.DependencyServiceImpl;
import datadog.telemetry.integration.IntegrationPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.util.AgentThreadFactory;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetrySystem {

  private static final long TELEMETRY_STOP_WAIT_MILLIS = 5000L;
  private static final Logger log = LoggerFactory.getLogger(TelemetrySystem.class);

  private static Thread TELEMETRY_THREAD;
  private static DependencyService DEPENDENCY_SERVICE;

  static DependencyService createDependencyService(Instrumentation instrumentation) {
    if (instrumentation != null) {
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
    TelemetryRunnable telemetryRunnable =
        new TelemetryRunnable(
            okHttpClient,
            telemetryService,
            Arrays.asList(
                new DependencyPeriodicAction(dependencyService), new IntegrationPeriodicAction()));
    return AgentThreadFactory.newAgentThread(
        AgentThreadFactory.AgentThread.TELEMETRY, telemetryRunnable);
  }

  public static void startTelemetry(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {

    // Eager initialization of Moshi: this prevents failing initialization if a SecurityManager is
    // set in main.
    // If it fails for any other reason, we better fail early and avoid starting telemetry.
    final Moshi moshi =
        new Moshi.Builder().add(new PolymorphicAdapterFactory(Payload.class)).build();
    // Ensure that each Payload adapter is initialized.
    moshi.adapter(AppStarted.class);
    // FIXME: GenerateMetrics serializer needs to be fixed.
    // moshi.adapter(GenerateMetrics.class);
    moshi.adapter(AppDependenciesLoaded.class);
    moshi.adapter(AppIntegrationsChange.class);
    final JsonAdapter<Telemetry> jsonAdapter = moshi.adapter(Telemetry.class);

    DependencyService dependencyService = createDependencyService(instrumentation);
    TelemetryService telemetryService =
        new TelemetryServiceImpl(
            new RequestBuilderSupplier(jsonAdapter, sco.agentUrl),
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
