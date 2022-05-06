package datadog.trace.common.writer;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.communication.monitor.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.TimeUnit;

public class DDIntakeWriter extends RemoteWriter {

  public static final String DEFAULT_INTAKE_VERSION = "v2";
  public static final long DEFAULT_INTAKE_TIMEOUT = 10; // timeout in seconds

  private static final int BUFFER_SIZE = 1024;

  public static DDIntakeWriterBuilder builder() {
    return new DDIntakeWriterBuilder();
  }

  public static class DDIntakeWriterBuilder {
    String site = Config.get().getSite();
    WellKnownTags wellKnownTags = Config.get().getWellKnownTags();
    TrackType trackType = TrackType.NOOP;
    String apiVersion = DEFAULT_INTAKE_VERSION;
    long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);
    int traceBufferSize = BUFFER_SIZE;
    HealthMetrics healthMetrics = new HealthMetrics(StatsDClient.NO_OP);
    int flushFrequencySeconds = 1;
    Monitoring monitoring = Monitoring.DISABLED;
    DroppingPolicy droppingPolicy = DroppingPolicy.DISABLED;
    Prioritization prioritization = Prioritization.FAST_LANE;
    private boolean alwaysFlush = true;

    private DDIntakeApi intakeApi;
    private String apiKey;

    public DDIntakeWriterBuilder intakeApi(final DDIntakeApi intakeApi) {
      this.intakeApi = intakeApi;
      return this;
    }

    public DDIntakeWriterBuilder apiKey(final String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public DDIntakeWriterBuilder site(final String site) {
      this.site = site;
      return this;
    }

    public DDIntakeWriterBuilder trackType(final TrackType trackType) {
      this.trackType = trackType;
      return this;
    }

    public DDIntakeWriterBuilder apiVersion(final String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    public DDIntakeWriterBuilder monitoring(final Monitoring monitoring) {
      this.monitoring = monitoring;
      return this;
    }

    public DDIntakeWriterBuilder timeoutMillis(long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    public DDIntakeWriterBuilder traceBufferSize(int traceBufferSize) {
      this.traceBufferSize = traceBufferSize;
      return this;
    }

    public DDIntakeWriterBuilder healthMetrics(final HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
      return this;
    }

    public DDIntakeWriterBuilder flushFrequencySeconds(int flushFrequencySeconds) {
      this.flushFrequencySeconds = flushFrequencySeconds;
      return this;
    }

    public DDIntakeWriterBuilder prioritization(final Prioritization prioritization) {
      this.prioritization = prioritization;
      return this;
    }

    public DDIntakeWriterBuilder droppingPolicy(final DroppingPolicy droppingPolicy) {
      this.droppingPolicy = droppingPolicy;
      return this;
    }

    public DDIntakeWriterBuilder wellKnownTags(final WellKnownTags wellKnownTags) {
      this.wellKnownTags = wellKnownTags;
      return this;
    }

    public DDIntakeWriterBuilder alwaysFlush(final boolean alwaysFlush) {
      this.alwaysFlush = alwaysFlush;
      return this;
    }

    public DDIntakeWriter build() {
      if (null == intakeApi) {
        intakeApi =
            DDIntakeApi.builder()
                .site(site)
                .trackType(trackType)
                .apiVersion(apiVersion)
                .apiKey(apiKey)
                .timeoutMillis(timeoutMillis)
                .build();
      }

      final DDIntakeMapperDiscovery mapperDiscovery =
          new DDIntakeMapperDiscovery(trackType, wellKnownTags);
      final PayloadDispatcher dispatcher =
          new PayloadDispatcher(mapperDiscovery, intakeApi, healthMetrics, monitoring);
      final TraceProcessingWorker traceProcessingWorker =
          new TraceProcessingWorker(
              traceBufferSize,
              healthMetrics,
              dispatcher,
              droppingPolicy,
              prioritization,
              flushFrequencySeconds,
              TimeUnit.SECONDS);

      return new DDIntakeWriter(
          intakeApi, healthMetrics, dispatcher, traceProcessingWorker, alwaysFlush);
    }
  }

  private DDIntakeWriter(
      RemoteApi api,
      HealthMetrics healthMetrics,
      Monitoring monitoring,
      TraceProcessingWorker worker,
      RemoteMapperDiscovery discovery) {
    this(
        api,
        healthMetrics,
        new PayloadDispatcher(discovery, api, healthMetrics, monitoring),
        worker,
        true);
  }

  protected DDIntakeWriter(
      RemoteApi api,
      HealthMetrics healthMetrics,
      PayloadDispatcher dispatcher,
      TraceProcessingWorker traceProcessingWorker,
      boolean alwaysFlush) {
    super(api, traceProcessingWorker, dispatcher, healthMetrics, alwaysFlush);
  }
}
