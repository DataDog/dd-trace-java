package com.datadog.iast;

import static com.datadog.iast.IastTag.ANALYZED;

import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reports IAST vulnerabilities. */
public class Reporter {

  private static final String VULNERABILITY_SPAN_NAME = "vulnerability";

  private final Predicate<Vulnerability> duplicated;

  Reporter() {
    this(Config.get());
  }

  public Reporter(final Config config) {
    this(config.isIastDeduplicationEnabled() ? new HashBasedDeduplication() : v -> false);
  }

  Reporter(final Predicate<Vulnerability> duplicate) {
    this.duplicated = duplicate;
  }

  public void report(@Nullable final AgentSpan span, @Nonnull final Vulnerability vulnerability) {
    if (span == null) {
      final AgentSpan newSpan = startNewSpan();
      try (final AgentScope autoClosed = tracer().activateSpan(newSpan, ScopeSource.MANUAL)) {
        vulnerability.getLocation().updateSpan(newSpan.getSpanId());
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
    final RequestContext reqCtx = span.getRequestContext();
    if (reqCtx == null) {
      return;
    }
    final IastRequestContext ctx = reqCtx.getData(RequestContextSlot.IAST);
    if (ctx == null) {
      return;
    }
    if (duplicated.test(vulnerability)) {
      return;
    }
    final VulnerabilityBatch batch = ctx.getVulnerabilityBatch();
    batch.add(vulnerability);
    if (!ctx.getAndSetSpanDataIsSet()) {
      final TraceSegment segment = reqCtx.getTraceSegment();
      segment.setDataTop("iast", batch);
      // Once we have added a vulnerability, try to override sampling and keep the trace.
      // TODO: We need to check if we can have an API with more fine-grained semantics on why traces
      // are kept.
      segment.setTagTop(DDTags.MANUAL_KEEP, true);
    }
  }

  private AgentSpan startNewSpan() {
    final AgentSpan.Context tagContext =
        new TagContext().withRequestContextDataIast(new IastRequestContext());
    final AgentSpan span =
        tracer()
            .startSpan(VULNERABILITY_SPAN_NAME, tagContext)
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

    public HashBasedDeduplication() {
      this(DEFAULT_MAX_SIZE);
    }

    HashBasedDeduplication(final int size) {
      maxSize = size;
      hashes = ConcurrentHashMap.newKeySet(size);
    }

    @Override
    public boolean test(final Vulnerability vulnerability) {
      final boolean newVulnerability = hashes.add(vulnerability.getHash());
      if (newVulnerability && hashes.size() > maxSize) {
        hashes.clear();
        hashes.add(vulnerability.getHash());
      }
      return !newVulnerability;
    }
  }
}
