package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.sampling.AdaptiveSampler;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JFRCheckpointer implements Checkpointer {
  private static final Logger log = LoggerFactory.getLogger(JFRCheckpointer.class);

  private static final int MASK = ~CPU;
  // these sampler parameters were chosen experimentally to prevent large 'overshoot'
  private static final int SAMPLER_LOOKBACK = 11;
  private static final int SAMPLER_WINDOW_SIZE_MS = 800;

  private final AdaptiveSampler sampler;

  private final LongAdder emitted = new LongAdder();
  private final LongAdder dropped = new LongAdder();
  private final int rateLimit;

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
    AgentSpan rootSpan = span.getLocalRootSpan();
    new CheckpointEvent(rootSpan.getSpanId().toLong(), span.getSpanId().toLong(), flags & MASK)
        .commit();
    emitted.increment();
  }

  void dropCheckpoint() {
    dropped.increment();
  }

  @Override
  public final void onRootSpan(final AgentSpan rootSpan, final boolean published) {
    new EndpointEvent(
            rootSpan.getResourceName().toString(), rootSpan.getSpanId().toLong(), published)
        .commit();
  }

  private void emitSummary() {
    new CheckpointSummaryEvent(rateLimit, emitted.sumThenReset(), dropped.sumThenReset()).commit();
  }

  private static AdaptiveSampler prepareSampler(final ConfigProvider configProvider) {
    final int limit = getRateLimit(configProvider);
    if (limit <= 0) {
      // adaptive sampling disabled
      log.debug("Checkpoint adaptive sampling is disabled");
      return null;
    }
    Duration windowSize = Duration.of(SAMPLER_WINDOW_SIZE_MS, ChronoUnit.MILLIS);

    /*
    Due to coarse grained sampling (at the level of a local root span) and extremely high variability of
    the number of checkpoints generated from such a root span, anywhere between 1 and 100000 seems to be
    quite common - with both fat tails, the expected limit must be somewhat adjusted since it will be
    almost always 'overshot' by a large margin.
    '0.6' seems to be the magic number to do the trick ...
    */
    final float limitPerMs = limit / 100000f; // (limit * 0.6f) / (60 * 1000)
    float samplesPerWindow = limitPerMs * windowSize.toMillis();

    if (samplesPerWindow < 1) {
      samplesPerWindow = 1;
      windowSize = Duration.of(1, ChronoUnit.MINUTES);
    }
    log.debug(
        "Using checkpoint adaptive sampling with parameters: windowSize(ms)={}, windowSamples={}, lookback={}",
        windowSize,
        samplesPerWindow,
        SAMPLER_LOOKBACK);
    return new AdaptiveSampler(windowSize, (int) samplesPerWindow, SAMPLER_LOOKBACK);
  }

  private static int getRateLimit(final ConfigProvider configProvider) {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_CHECKPOINTS_RATE_LIMIT,
        ProfilingConfig.PROFILING_CHECKPOINTS_RATE_LIMIT_DEFAULT);
  }
}
