package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.sampling.AdaptiveSampler;
import datadog.trace.api.sampling.ConstantSampler;
import datadog.trace.api.sampling.Sampler;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.EndpointTracker;
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

  static class SamplerConfig {
    final Duration windowSize;
    final int samplesPerWindow;
    final int averageLookback;
    final int budgetLookback;

    SamplerConfig(
        @Nonnull Duration windowSize,
        int samplesPerWindow,
        int averageLookback,
        int budgetLookback) {
      this.windowSize = windowSize;
      this.samplesPerWindow = samplesPerWindow;
      this.averageLookback = averageLookback;
      this.budgetLookback = budgetLookback;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SamplerConfig that = (SamplerConfig) o;
      return samplesPerWindow == that.samplesPerWindow
          && averageLookback == that.averageLookback
          && budgetLookback == that.budgetLookback
          && windowSize.equals(that.windowSize);
    }

    @Override
    public int hashCode() {
      return Objects.hash(windowSize, samplesPerWindow, averageLookback, budgetLookback);
    }
  }

  static class ConfiguredSampler {
    final SamplerConfig config;
    final Sampler sampler;

    ConfiguredSampler(SamplerConfig config, Sampler sampler) {
      this.config = config;
      this.sampler = sampler;
    }
  }

  private static final int MASK = ~CPU;
  private static final int MIN_SAMPLER_LOOKBACK = 3;
  private static final int DEFAULT_AVERAGE_LOOKBACK = 16;
  static final int MIN_SAMPLER_WINDOW_SIZE_MS = 100;
  static final int MAX_SAMPLER_WINDOW_SIZE_MS = 30000;
  static final int MAX_SAMPLER_RATE = 1000000; // max 1M checkpoints per recording

  private final Sampler sampler;

  private final LongAdder emitted = new LongAdder();
  private final LongAdder dropped = new LongAdder();
  private final int rateLimit;
  private final boolean isEndpointCollectionEnabled;
  private final SamplerConfig samplerConfig;

  public JFRCheckpointer() {
    this(ConfigProvider.getInstance());
  }

  JFRCheckpointer(final ConfigProvider configProvider) {
    this(prepareSampler(configProvider), configProvider);
  }

  private JFRCheckpointer(final ConfiguredSampler sampler, final ConfigProvider configProvider) {
    this(sampler.sampler, sampler.config, configProvider);
  }

  JFRCheckpointer(final Sampler sampler, final ConfigProvider configProvider) {
    this(sampler, null, configProvider);
  }

  JFRCheckpointer(
      final Sampler sampler,
      final SamplerConfig samplerConfig,
      final ConfigProvider configProvider) {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads JFR classes - which may not be present on some JVMs
    EventType.getEventType(CheckpointEvent.class);
    EventType.getEventType(EndpointEvent.class);
    EventType.getEventType(CheckpointSummaryEvent.class);

    this.samplerConfig = samplerConfig;
    rateLimit = getRateLimit(configProvider);
    this.sampler = Objects.requireNonNull(sampler);

    if (sampler != null) {
      FlightRecorder.addPeriodicEvent(CheckpointSamplerConfigEvent.class, this::emitSamplerConfig);
      FlightRecorder.addPeriodicEvent(CheckpointSummaryEvent.class, this::emitSummary);
    }

    isEndpointCollectionEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
  }

  @Override
  public final void checkpoint(final AgentSpan span, final int flags) {
    tryEmitCheckpoint(span, flags);
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
  public final void onRootSpanWritten(
      final AgentSpan rootSpan, final boolean traceSampled, final boolean checkpointsSampled) {
    if (isEndpointCollectionEnabled) {
      if (rootSpan instanceof DDSpan) {
        DDSpan span = (DDSpan) rootSpan;
        EndpointTracker tracker = span.getEndpointTracker();
        if (tracker != null) {
          tracker.endpointWritten(span, traceSampled, checkpointsSampled);
        }
      }
    }
  }

  @Override
  public void onRootSpanStarted(AgentSpan rootSpan) {
    if (isEndpointCollectionEnabled) {
      if (rootSpan instanceof DDSpan) {
        DDSpan span = (DDSpan) rootSpan;
        span.setEndpointTracker(new EndpointEvent(span));
      }
    }
  }

  private void emitSummary() {
    new CheckpointSummaryEvent(rateLimit, emitted.sumThenReset(), dropped.sumThenReset()).commit();
  }

  private void emitSamplerConfig() {
    new CheckpointSamplerConfigEvent(
            samplerConfig.windowSize.toMillis(),
            samplerConfig.samplesPerWindow,
            samplerConfig.averageLookback,
            samplerConfig.budgetLookback)
        .commit();
  }

  private static ConfiguredSampler prepareSampler(final ConfigProvider configProvider) {
    SamplerConfig config = getSamplerConfiguration(configProvider);
    if (config == null) {
      // adaptive sampling disabled
      log.debug("Checkpoint adaptive sampling is disabled");
      return new ConfiguredSampler(null, new ConstantSampler(true));
    }
    log.debug(
        "Using checkpoint adaptive sampling with parameters: windowSize(ms)={}, windowSamples={}, lookback={}",
        config.windowSize.toMillis(),
        config.samplesPerWindow,
        config.budgetLookback);
    return new ConfiguredSampler(
        config,
        new AdaptiveSampler(
            config.windowSize,
            config.samplesPerWindow,
            8,
            config.budgetLookback,
            JFRCheckpointer::emitWindowConfig));
  }

  private static void emitWindowConfig(
      long totalCount, long sampledCount, long budget, double totalAverage, double probability) {
    new CheckpointSamplerReconfigEvent(totalCount, sampledCount, budget, totalAverage, probability)
        .commit();
  }

  static SamplerConfig getSamplerConfiguration(ConfigProvider configProvider) {
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
    int budgetLookback =
        Math.round(Math.max((0.8f * limit) / samplesPerWindow, MIN_SAMPLER_LOOKBACK));

    return new SamplerConfig(
        windowSize, Math.round(samplesPerWindow), DEFAULT_AVERAGE_LOOKBACK, budgetLookback);
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
