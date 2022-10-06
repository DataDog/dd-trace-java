package com.datadog.iast;

import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Reports IAST vulnerabilities. */
public class Reporter {

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

  public void report(final AgentSpan span, final Vulnerability vulnerability) {
    if (span == null) {
      return;
    }
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
