package com.datadog.iast.sink;

import com.datadog.iast.HasDependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.Reporter;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.model.VulnerabilityType.InjectionType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.Ranges.RangesProvider;
import com.datadog.iast.taint.TaintedObject;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie;
import datadog.trace.util.stacktrace.StackWalker;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Base class with utility methods for with sinks */
public abstract class SinkModuleBase implements HasDependencies {

  protected OverheadController overheadController;
  protected Reporter reporter;
  protected StackWalker stackWalker;

  @Override
  public void registerDependencies(@Nonnull final Dependencies dependencies) {
    overheadController = dependencies.getOverheadController();
    reporter = dependencies.getReporter();
    stackWalker = dependencies.getStackWalker();
  }

  protected final <E> @Nullable Evidence checkInjection(
      @Nullable final AgentSpan span,
      @Nonnull final IastRequestContext ctx,
      @Nonnull final InjectionType type,
      @Nonnull final E value) {
    TaintedObject taintedObject = ctx.getTaintedObjects().get(value);
    if (taintedObject == null) {
      return null;
    }
    Range[] ranges = Ranges.getNotMarkedRanges(taintedObject.getRanges(), type.mark());
    if (ranges == null || ranges.length == 0) {
      return null;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return null;
    }
    final Evidence result = new Evidence(value.toString(), ranges);
    report(span, type, result);
    return result;
  }

  protected final <E> @Nullable Evidence checkInjection(
      @Nullable final AgentSpan span,
      @Nonnull final InjectionType type,
      @Nonnull final RangesProvider<E> rangeProvider) {
    final int rangeCount = rangeProvider.rangeCount();
    if (rangeCount == 0) {
      return null;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return null;
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

    Range[] notMarkedRanges = Ranges.getNotMarkedRanges(targetRanges, type.mark());
    if (notMarkedRanges == null || notMarkedRanges.length == 0) {
      return null;
    }

    final Evidence result = new Evidence(evidence, notMarkedRanges);
    report(span, type, result);
    return result;
  }

  protected final <E> @Nullable Evidence checkInjection(
      @Nullable final AgentSpan span,
      @Nonnull final InjectionType type,
      @Nonnull final RangesProvider<E>... rangeProviders) {
    int rangeCount = 0;
    for (final RangesProvider<E> provider : rangeProviders) {
      rangeCount += provider.rangeCount();
    }
    if (rangeCount == 0) {
      return null;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return null;
    }
    final StringBuilder evidence = new StringBuilder();
    final Range[] targetRanges = new Range[rangeCount];
    int rangeIndex = 0;
    for (final RangesProvider<E> rangeProvider : rangeProviders) {
      for (int i = 0; i < rangeProvider.size(); i++) {
        final E item = rangeProvider.value(i);
        if (item != null) {
          if (evidence.length() > 0) {
            evidence.append(type.evidenceSeparator());
          }
          final Range[] taintedRanges = rangeProvider.ranges(item);
          if (taintedRanges != null) {
            Ranges.copyShift(taintedRanges, targetRanges, rangeIndex, evidence.length());
            rangeIndex += taintedRanges.length;
          }
          evidence.append(item);
        }
      }
    }
    Range[] notMarkedRanges = Ranges.getNotMarkedRanges(targetRanges, type.mark());
    if (notMarkedRanges == null || notMarkedRanges.length == 0) {
      return null;
    }
    final Evidence result = new Evidence(evidence.toString(), notMarkedRanges);
    report(span, type, result);
    return result;
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

  static long spanId(final AgentSpan span) {
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
