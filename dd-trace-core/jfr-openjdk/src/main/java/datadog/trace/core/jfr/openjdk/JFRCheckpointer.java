package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.sampling.AdaptiveSampler;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JFRCheckpointer implements Checkpointer {
  private static final Logger log = LoggerFactory.getLogger(JFRCheckpointer.class);

  static class AdaptiveSamplerConfig {
    final Duration windowSize;
    final int samplesPerWindow;
    final int lookBackWindows;

    AdaptiveSamplerConfig(@Nonnull Duration windowSize, int samplesPerWindow, int lookBackWindows) {
      this.windowSize = windowSize;
      this.samplesPerWindow = samplesPerWindow;
      this.lookBackWindows = lookBackWindows;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AdaptiveSamplerConfig that = (AdaptiveSamplerConfig) o;
      return samplesPerWindow == that.samplesPerWindow
          && lookBackWindows == that.lookBackWindows
          && Objects.equals(windowSize, that.windowSize);
    }

    @Override
    public int hashCode() {
      return Objects.hash(windowSize, samplesPerWindow, lookBackWindows);
    }
  }

  private static final int MASK = ~CPU;
  private static final int MIN_SAMPLER_LOOKBACK = 3;
  static final int MIN_SAMPLER_WINDOW_SIZE_MS = 100;
  static final int MAX_SAMPLER_WINDOW_SIZE_MS = 30000;
  static final int MAX_SAMPLER_RATE = 1000000; // max 1M checkpoints per recording

  private final AdaptiveSampler sampler;

  private final LongAdder emitted = new LongAdder();
  private final LongAdder dropped = new LongAdder();
  private final int rateLimit;
  private final boolean isEndpointCollectionEnabled;

  public JFRCheckpointer() {
    this(ConfigProvider.getInstance());
  }

  JFRCheckpointer(final ConfigProvider configProvider) {
    this(prepareSampler(configProvider), configProvider);
  }

  JFRCheckpointer(final AdaptiveSampler sampler, final ConfigProvider configProvider) {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads JFR classes - which may not be present on some JVMs
    EventType.getEventType(CheckpointEvent.class);
    EventType.getEventType(EndpointEvent.class);
    EventType.getEventType(CheckpointSummaryEvent.class);

    rateLimit = getRateLimit(configProvider);
    this.sampler = sampler;

    if (sampler != null) {
      FlightRecorder.addPeriodicEvent(CheckpointSummaryEvent.class, this::emitSummary);
    }

    isEndpointCollectionEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
  }

  @Override
  public final void checkpoint(final AgentSpan span, final int flags) {
    if (sampler != null) {
      tryEmitCheckpoint(span, flags);
    } else {
      emitCheckpoint(span, flags);
    }
  }

  private void tryEmitCheckpoint(final AgentSpan span, final int flags) {
    final boolean checkpointed;
    final Boolean isEmitting = span.isEmittingCheckpoints();
    if (isEmitting == null) {
      /*
      While this branch is race-prone under general circumstances here we can safely ignore it.
      The local root span will be created just once from exactly one thread and this branch
      will effectively be taken only when 'span' is the local root span and the first checkpoint
      (usually 'start span') is emitted.
      Things might become problematic when child spans start emitting checkpoints before the
      local root span has been created but that is obvously invalid behaviour, breaking the whole tracer.
      Therefore, we can afford to omit proper synchronization here and rely only on the span internal
      checkpoint emission flag being properly published (eg. via 'volatile').
       */
      // if the flag hasn't been set yet consult the sampler
      checkpointed = sampler.sample();
      // store the decision in the span
      span.setEmittingCheckpoints(checkpointed);
      if (log.isDebugEnabled()) {
        log.debug(
            "{} checkpoints for span(s_id={}, t_id={}) subtree",
            checkpointed ? "Generating" : "Dropping",
            span.getSpanId(),
            span.getTraceId());
      }
    } else if (isEmitting) {
      // reuse the sampler decision and force the sample
      checkpointed = sampler.keep();
    } else {
      // reuse the sampler decision and force the drop
      checkpointed = sampler.drop();
    }
    if (checkpointed) {
      emitCheckpoint(span, flags);
    } else {
      dropCheckpoint();
    }
  }

  void emitCheckpoint(final AgentSpan span, final int flags) {
    final AgentSpan rootSpan = span.getLocalRootSpan();
    new CheckpointEvent(rootSpan.getSpanId().toLong(), span.getSpanId().toLong(), flags & MASK)
        .commit();
    emitted.increment();
  }

  void dropCheckpoint() {
    dropped.increment();
  }

  @Override
  public final void onRootSpan(
      final AgentSpan rootSpan, final boolean traceSampled, final boolean checkpointsSampled) {
    if (isEndpointCollectionEnabled) {
      new EndpointEvent(
              rootSpan.getResourceName().toString(),
              rootSpan.getTraceId().toLong(),
              rootSpan.getSpanId().toLong(),
              traceSampled,
              checkpointsSampled)
          .commit();
    }
  }

  private void emitSummary() {
    new CheckpointSummaryEvent(rateLimit, emitted.sumThenReset(), dropped.sumThenReset()).commit();
  }

  private static AdaptiveSampler prepareSampler(final ConfigProvider configProvider) {
    AdaptiveSamplerConfig config = getSamplerConfiguration(configProvider);
    if (config == null) {
      // adaptive sampling disabled
      log.debug("Checkpoint adaptive sampling is disabled");
      return null;
    }
    log.debug(
        "Using checkpoint adaptive sampling with parameters: windowSize(ms)={}, windowSamples={}, lookback={}",
        config.windowSize.toMillis(),
        config.samplesPerWindow,
        config.lookBackWindows);
    return new AdaptiveSampler(config.windowSize, config.samplesPerWindow, config.lookBackWindows);
  }

  static AdaptiveSamplerConfig getSamplerConfiguration(ConfigProvider configProvider) {
    final int limit = getRateLimit(configProvider);
    if (limit <= 0) {
      return null;
    }
    Duration windowSize = Duration.of(getSamplerWindowMs(configProvider), ChronoUnit.MILLIS);

    // the rate limit is defined per 1 minute (recording length) - need to divide it by 60000 to get
    // the value per ms
    final float limitPerMs = limit / 60000f;
    float samplesPerWindow = limitPerMs * windowSize.toMillis();

    if (samplesPerWindow > 0) {
      while (samplesPerWindow < 10) {
        samplesPerWindow *= 10;
        windowSize = windowSize.multipliedBy(10);
      }
    }
    if (windowSize.toMillis() > MAX_SAMPLER_WINDOW_SIZE_MS) {
      // the requested sampling rate is very low; default to the max sampler window size and 1
      // sample
      windowSize = Duration.of(MAX_SAMPLER_WINDOW_SIZE_MS, ChronoUnit.MILLIS);
      samplesPerWindow = 1;
    }

    // adjust the lookback parameter to represent ~80% of the requested rate
    int samplerLookback =
        Math.round(Math.max((0.8f * limit) / samplesPerWindow, MIN_SAMPLER_LOOKBACK));

    return new AdaptiveSamplerConfig(windowSize, Math.round(samplesPerWindow), samplerLookback);
  }

  private static int getRateLimit(final ConfigProvider configProvider) {
    return Math.min(
        configProvider.getInteger(
            ProfilingConfig.PROFILING_CHECKPOINTS_SAMPLER_RATE_LIMIT,
            ProfilingConfig.PROFILING_CHECKPOINTS_SAMPLER_RATE_LIMIT_DEFAULT),
        MAX_SAMPLER_RATE);
  }

  private static int getSamplerWindowMs(final ConfigProvider configProvider) {
    return Math.max(
        Math.min(
            configProvider.getInteger(
                ProfilingConfig.PROFILING_CHECKPOINTS_SAMPLER_WINDOW_MS,
                ProfilingConfig.PROFILING_CHECKPOINTS_SAMPLER_WINDOW_MS_DEFAULT),
            MAX_SAMPLER_WINDOW_SIZE_MS),
        MIN_SAMPLER_WINDOW_SIZE_MS);
  }
}
