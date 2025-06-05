package com.datadog.iast;

import static com.datadog.iast.IastTag.Enabled.ANALYZED;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.stacktrace.StackTraceEvent.DEFAULT_LANGUAGE;

import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.*;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import datadog.trace.util.stacktrace.StackUtils;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reports IAST vulnerabilities. */
public class Reporter {

  private static final Logger LOG = LoggerFactory.getLogger(Reporter.class);

  private static final String IAST_TAG = "iast";

  private static final String VULNERABILITY_SPAN_NAME = "vulnerability";
  public static final String METASTRUCT_VULNERABILITY = "vulnerability";

  private final Predicate<Vulnerability> duplicated;

  Reporter() {
    this(Config.get(), null);
  }

  public Reporter(final Config config, @Nullable final AgentTaskScheduler taskScheduler) {
    this(
        config.isIastDeduplicationEnabled()
            ? new HashBasedDeduplication(taskScheduler)
            : v -> false);
  }

  Reporter(final Predicate<Vulnerability> duplicate) {
    this.duplicated = duplicate;
  }

  public void report(@Nullable final AgentSpan span, @Nonnull final Vulnerability vulnerability) {
    if (duplicated.test(vulnerability)) {
      return;
    }
    if (span == null) {
      final AgentSpan newSpan = startNewSpan();
      try (final AgentScope autoClosed = tracer().activateManualSpan(newSpan)) {
        vulnerability.updateSpan(newSpan);
        reportVulnerability(newSpan, vulnerability);
      } finally {
        newSpan.finish();
      }
    } else {
      reportVulnerability(span, vulnerability);
    }
  }

  private void reportVulnerability(
      @Nonnull final AgentSpan span, @Nonnull final Vulnerability vulnerability) {
    final VulnerabilityBatch batch = getOrCreateVulnerabilityBatch(span);
    if (batch != null) {
      batch.add(vulnerability);
      if (Config.get().isIastStackTraceEnabled()
          && batch.getVulnerabilities() != null
          && vulnerability.getLocation().getStackId() == null) {
        String stackId =
            addVulnerabilityStackTrace(span, String.valueOf(batch.getVulnerabilities().size()));
        if (stackId != null) {
          vulnerability.getLocation().setStackId(stackId);
        }
      }
    }
  }

  @Nullable
  private static String addVulnerabilityStackTrace(@Nonnull AgentSpan span, String index) {
    final RequestContext reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return null;
    }
    List<StackTraceFrame> frames = StackUtils.generateUserCodeStackTrace();
    StackTraceEvent stackTraceEvent = new StackTraceEvent(frames, DEFAULT_LANGUAGE, index, null);
    StackUtils.addStacktraceEventsToMetaStruct(
        reqCtx, METASTRUCT_VULNERABILITY, Collections.singletonList(stackTraceEvent));
    return stackTraceEvent.getId();
  }

  @Nullable
  private VulnerabilityBatch getOrCreateVulnerabilityBatch(final AgentSpan span) {
    final RequestContext reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return null;
    }
    final TraceSegment segment = reqCtx.getTraceSegment();
    final Object current = segment.getDataTop(IAST_TAG);
    if (current instanceof VulnerabilityBatch) {
      return (VulnerabilityBatch) current;
    }
    if (current == null) {
      final IastRequestContext iastCtx = reqCtx.getData(RequestContextSlot.IAST);
      final VulnerabilityBatch batch;
      if (iastCtx != null) {
        batch = iastCtx.getVulnerabilityBatch();
      } else {
        // in case the request is not handled by IAST create a new one and set the analyzed flag
        batch = new VulnerabilityBatch();
        ANALYZED.setTagTop(segment);
      }
      segment.setDataTop(IAST_TAG, batch);
      // Once we have added a vulnerability, try to override sampling and keep the trace.
      // TODO: We need to check if we can have an API with more fine-grained semantics on why traces
      // are kept.
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      return batch;
    }

    // TODO: can we do better here? (maybe create a new span)
    LOG.debug(SEND_TELEMETRY, "Cannot attach vulnerability to span, current={}", current);
    return null;
  }

  private AgentSpan startNewSpan() {
    final AgentSpanContext tagContext =
        new TagContext()
            .withRequestContextDataIast(new IastRequestContext(TaintedObjects.NoOp.INSTANCE));
    final AgentSpan span =
        tracer()
            .startSpan("iast", VULNERABILITY_SPAN_NAME, tagContext)
            .setSpanType(InternalSpanTypes.VULNERABILITY);
    ANALYZED.setTag(span);
    return span;
  }

  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }

  /**
   * This class maintains a set of vulnerability hashes that have already been reported, we don't
   * care about thread safety too much as an occasional duplicated report is not a big deal.
   */
  protected static class HashBasedDeduplication implements Predicate<Vulnerability> {

    private static final int DEFAULT_MAX_SIZE = 1000;

    private final int maxSize;

    private final Set<Long> hashes;

    public HashBasedDeduplication(@Nullable final AgentTaskScheduler taskScheduler) {
      this(DEFAULT_MAX_SIZE, taskScheduler);
    }

    HashBasedDeduplication(final int size, @Nullable final AgentTaskScheduler taskScheduler) {
      maxSize = size;
      hashes = ConcurrentHashMap.newKeySet(size);
      if (taskScheduler != null) {
        // Reset deduplication cache every hour. This helps the backend when calculating exposure
        // windows, by sending
        // the same vulnerabilities from time to time.
        taskScheduler.scheduleAtFixedRate(hashes::clear, 1, 1, TimeUnit.HOURS);
      }
    }

    @Override
    public boolean test(final Vulnerability vulnerability) {
      if (!vulnerability.getType().isDeduplicable()) {
        return false;
      }
      final boolean newVulnerability = hashes.add(vulnerability.getHash());
      if (newVulnerability && hashes.size() > maxSize) {
        hashes.clear();
        hashes.add(vulnerability.getHash());
      }
      return !newVulnerability;
    }
  }
}
