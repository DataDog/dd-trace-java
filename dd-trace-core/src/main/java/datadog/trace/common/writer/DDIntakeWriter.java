package datadog.trace.common.writer;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DDIntakeWriter extends RemoteWriter {

  public static final String DEFAULT_INTAKE_VERSION = "v2";
  public static final long DEFAULT_INTAKE_TIMEOUT = 10; // timeout in seconds

  private static final int BUFFER_SIZE = 1024;

  public static DDIntakeWriterBuilder builder() {
    return new DDIntakeWriterBuilder();
  }

  public static class DDIntakeWriterBuilder {
    CiVisibilityWellKnownTags wellKnownTags = Config.get().getCiVisibilityWellKnownTags();
    int traceBufferSize = BUFFER_SIZE;
    HealthMetrics healthMetrics = HealthMetrics.NO_OP;
    int flushIntervalMilliseconds = 1000;
    Monitoring monitoring = Monitoring.DISABLED;
    DroppingPolicy droppingPolicy = DroppingPolicy.DISABLED;
    Prioritization prioritization = Prioritization.FAST_LANE;
    private int flushTimeout = 5;
    private TimeUnit flushTimeoutUnit = TimeUnit.SECONDS;
    private boolean alwaysFlush = false;

    private final Map<TrackType, RemoteApi> tracks = new EnumMap<>(TrackType.class);

    private SingleSpanSampler singleSpanSampler;

    public DDIntakeWriterBuilder addTrack(final TrackType trackType, final RemoteApi intakeApi) {
      tracks.put(trackType, intakeApi);
      return this;
    }

    public DDIntakeWriterBuilder monitoring(final Monitoring monitoring) {
      this.monitoring = monitoring;
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

    public DDIntakeWriterBuilder flushIntervalMilliseconds(int flushIntervalMilliseconds) {
      this.flushIntervalMilliseconds = flushIntervalMilliseconds;
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

    public DDIntakeWriterBuilder wellKnownTags(final CiVisibilityWellKnownTags wellKnownTags) {
      this.wellKnownTags = wellKnownTags;
      return this;
    }

    public DDIntakeWriterBuilder alwaysFlush(final boolean alwaysFlush) {
      this.alwaysFlush = alwaysFlush;
      return this;
    }

    public DDIntakeWriterBuilder flushTimeout(
        final int flushTimeout, final TimeUnit flushTimeoutUnit) {
      this.flushTimeout = flushTimeout;
      this.flushTimeoutUnit = flushTimeoutUnit;
      return this;
    }

    public DDIntakeWriterBuilder singleSpanSampler(SingleSpanSampler singleSpanSampler) {
      this.singleSpanSampler = singleSpanSampler;
      return this;
    }

    public DDIntakeWriter build() {
      if (tracks.isEmpty()) {
        throw new IllegalArgumentException("At least one track needs to be configured");
      }

      PayloadDispatcher dispatcher;
      if (tracks.size() == 1) {
        dispatcher = createDispatcher(tracks.entrySet().iterator().next());
      } else {
        PayloadDispatcher[] dispatchers =
            tracks.entrySet().stream()
                .map(this::createDispatcher)
                .toArray(PayloadDispatcher[]::new);
        dispatcher = new CompositePayloadDispatcher(dispatchers);
      }

      final TraceProcessingWorker traceProcessingWorker =
          new TraceProcessingWorker(
              traceBufferSize,
              healthMetrics,
              dispatcher,
              droppingPolicy,
              prioritization,
              flushIntervalMilliseconds,
              TimeUnit.MILLISECONDS,
              singleSpanSampler);

      return new DDIntakeWriter(
          traceProcessingWorker,
          dispatcher,
          healthMetrics,
          flushTimeout,
          flushTimeoutUnit,
          alwaysFlush);
    }

    private PayloadDispatcher createDispatcher(Map.Entry<TrackType, RemoteApi> e) {
      TrackType trackType = e.getKey();
      RemoteApi intakeApi = e.getValue();
      DDIntakeMapperDiscovery mapperDiscovery =
          new DDIntakeMapperDiscovery(trackType, wellKnownTags, intakeApi.isCompressionEnabled());
      return new PayloadDispatcherImpl(mapperDiscovery, intakeApi, healthMetrics, monitoring);
    }
  }

  DDIntakeWriter(
      TraceProcessingWorker worker,
      PayloadDispatcher dispatcher,
      HealthMetrics healthMetrics,
      int flushTimeout,
      TimeUnit flushTimeoutUnit,
      boolean alwaysFlush) {
    super(worker, dispatcher, healthMetrics, flushTimeout, flushTimeoutUnit, alwaysFlush);
  }

  DDIntakeWriter(
      TraceProcessingWorker worker,
      PayloadDispatcher dispatcher,
      HealthMetrics healthMetrics,
      boolean alwaysFlush) {
    super(worker, dispatcher, healthMetrics, alwaysFlush);
  }
}
