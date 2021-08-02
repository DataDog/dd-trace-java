package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.DDId;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.sampling.AdaptiveSampler;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JFRCheckpointer implements Checkpointer {
  private static final Logger log = LoggerFactory.getLogger(JFRCheckpointer.class);

  private static final int MASK = ~CPU;

  private final AdaptiveSampler sampler;

  private final LongAdder emitted = new LongAdder();
  private final LongAdder dropped = new LongAdder();
  private final int rateLimit;

  public JFRCheckpointer() {
    this(ConfigProvider.getInstance());
  }

  JFRCheckpointer(ConfigProvider configProvider) {
    this(prepareSampler(configProvider), configProvider);
  }

  JFRCheckpointer(AdaptiveSampler sampler, ConfigProvider configProvider) {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads JFR classes - which may not be present on some JVMs
    EventType.getEventType(CheckpointEvent.class);
    EventType.getEventType(EndpointEvent.class);
    EventType.getEventType(CheckpointSummaryEvent.class);

    this.rateLimit = getRateLimit(configProvider);
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
      emitCheckpoint(span.getTraceId(), span.getSpanId(), flags);
    }
  }

  private void tryEmitCheckpoint(AgentSpan span, int flags) {
    boolean checkpointed = false;
    Boolean isEmitting = span.isEmittingCheckpoints();
    if (isEmitting == null) {
      checkpointed = sampler.sample();
      span.setEmittingCheckpoints(checkpointed);
      if (log.isDebugEnabled()) {
        log.debug(
            "{} checkpoints for root span(s_id={}, t_id={})",
            checkpointed ? "Generating" : "Dropping",
            span.getSpanId(),
            span.getTraceId());
      }
    } else if (isEmitting) {
      checkpointed = sampler.keep();
    } else {
      checkpointed = sampler.drop();
    }
    if (checkpointed) {
      emitCheckpoint(span.getTraceId(), span.getSpanId(), flags);
    } else {
      dropped.increment();
    }
  }

  void emitCheckpoint(DDId traceId, DDId spanId, int flags) {
    new CheckpointEvent(traceId.toLong(), spanId.toLong(), flags & MASK).commit();
    emitted.increment();
  }

  @Override
  public final void onRootSpanPublished(final String endpoint, final DDId traceId) {
    new EndpointEvent(endpoint, traceId.toLong()).commit();
  }

  private void emitSummary() {
    new CheckpointSummaryEvent(rateLimit, emitted.sumThenReset(), dropped.sumThenReset()).commit();
  }

  private static AdaptiveSampler prepareSampler(ConfigProvider configProvider) {
    int limit = getRateLimit(configProvider);
    if (limit <= 0) {
      // adaptive sampling disabled
      log.debug("Checkpoint adaptive sampling is disabled");
      return null;
    }
    TimeUnit windowTimeUnit = TimeUnit.MILLISECONDS;
    int windowSize = 800;
    /*
    Due to coarse grained sampling (at the level of a local root span) and extremely high variability of
    the number of checkpoints generated from such a root span, anywhere between 1 and 100000 seems to be
    quite common - with both fat tails, the expected limit must be somewhat adjusted since it will be
    almost always 'overshot' by a large margin.
    '0.6' seems to be the magic number to do the trick ...
    */
    float limitPerMs = (limit * 0.6f) / windowTimeUnit.convert(1, TimeUnit.MINUTES);
    float samplesPerWindow = limitPerMs * windowSize;

    if (samplesPerWindow < 1) {
      windowTimeUnit = TimeUnit.MINUTES;
      samplesPerWindow = 1;
      windowSize = 1;
    }
    int lookback = 11;
    log.debug(
        "Using checkpoint adaptive sampling with parameters: windowSize(ms)={}, windowSamples={}, lookback={}",
        windowSize,
        samplesPerWindow,
        lookback);
    return AdaptiveSampler.instance(windowSize, windowTimeUnit, (int) samplesPerWindow, lookback);
  }

  private static String decodeCheckpointFlags(int flags) {
    if ((flags & SPAN) == SPAN) {
      return ((flags & END) == END) ? "END_SPAN" : "START_SPAN";
    }
    if ((flags & CPU) == CPU) {
      return ((flags & END) == END) ? "END_TASK" : "START_TASK";
    }
    if ((flags & THREAD_MIGRATION) == THREAD_MIGRATION) {
      return ((flags & END) == END) ? "RESUME_SPAN" : "SUSPEND_SPAN";
    }
    return "UNKNOWN(" + flags + ")";
  }

  private static int getRateLimit(ConfigProvider configProvider) {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_CHECKPOINTS_RATE_LIMIT,
        ProfilingConfig.PROFILING_CHECKPOINTS_RATE_LIMIT_DEFAULT);
  }
}
