package com.datadog.iast.sink;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.model.VulnerabilityType.InjectionType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.Ranges.RangesProvider;
import com.datadog.iast.taint.TaintedObject;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Base class with utility methods for with sinks */
public abstract class SinkModuleBase extends IastModuleBase {

  protected final <E> void checkInjection(
      @Nullable final AgentSpan span,
      @Nonnull final IastRequestContext ctx,
      @Nonnull final InjectionType type,
      @Nonnull final E value) {
    TaintedObject taintedObject = ctx.getTaintedObjects().get(value);
    if (taintedObject == null) {
      return;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    report(span, type, new Evidence(value.toString(), taintedObject.getRanges()));
  }

  protected final <E> void checkInjection(
      @Nullable final AgentSpan span,
      @Nonnull final InjectionType type,
      @Nonnull final RangesProvider<E> rangeProvider) {
    final int rangeCount = rangeProvider.rangeCount();
    if (rangeCount == 0) {
      return;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    String evidence;
    Range[] targetRanges;
    if (rangeProvider.size() == 1) {
      // only one item and has ranges
      final E item = rangeProvider.value(0);
      evidence = item.toString();
      targetRanges = rangeProvider.ranges(item);
    } else {
      final StringBuilder builder = new StringBuilder();
      targetRanges = new Range[rangeCount];
      int rangeIndex = 0;
      for (int i = 0; i < rangeProvider.size(); i++) {
        final E item = rangeProvider.value(i);
        if (item != null) {
          if (builder.length() > 0) {
            builder.append(type.evidenceSeparator());
          }
          final Range[] taintedRanges = rangeProvider.ranges(item);
          if (taintedRanges != null) {
            Ranges.copyShift(taintedRanges, targetRanges, rangeIndex, builder.length());
            rangeIndex += taintedRanges.length;
          }
          builder.append(item);
        }
      }
      evidence = builder.toString();
    }

    report(span, type, new Evidence(evidence, targetRanges));
  }

  protected final void report(
      @Nullable final AgentSpan span,
      @Nonnull final VulnerabilityType type,
      @Nonnull final Evidence evidence) {
    reporter.report(
        span,
        new Vulnerability(
            type, Location.forSpanAndStack(spanId(span), getCurrentStackTrace()), evidence));
  }

  protected StackTraceElement getCurrentStackTrace() {
    return stackWalker.walk(SinkModuleBase::findValidPackageForVulnerability);
  }

  private static long spanId(final AgentSpan span) {
    return span == null ? 0 : span.getSpanId();
  }

  static StackTraceElement findValidPackageForVulnerability(
      @Nonnull final Stream<StackTraceElement> stream) {
    final StackTraceElement[] first = new StackTraceElement[1];
    return stream
        .filter(
            stack -> {
              if (first[0] == null) {
                first[0] = stack;
              }
              return IastExclusionTrie.apply(stack.getClassName()) < 1;
            })
        .findFirst()
        .orElse(first[0]);
  }
}
