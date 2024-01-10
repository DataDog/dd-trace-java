package com.datadog.iast.sink;

import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static com.datadog.iast.util.ObjectVisitor.State.EXIT;

import com.datadog.iast.Dependencies;
import com.datadog.iast.Reporter;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.model.VulnerabilityType.InjectionType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.Ranges.RangesProvider;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.ObjectVisitor;
import com.datadog.iast.util.ObjectVisitor.State;
import com.datadog.iast.util.ObjectVisitor.Visitor;
import datadog.trace.api.iast.IastContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie;
import datadog.trace.util.stacktrace.StackWalker;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Base class with utility methods for with sinks */
@SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
public abstract class SinkModuleBase {

  protected final OverheadController overheadController;
  protected final Reporter reporter;
  protected final StackWalker stackWalker;

  protected SinkModuleBase(@Nonnull final Dependencies dependencies) {
    overheadController = dependencies.getOverheadController();
    reporter = dependencies.getReporter();
    stackWalker = dependencies.getStackWalker();
  }

  protected final <E> @Nullable Evidence checkInjection(
      @Nonnull final IastContext ctx, @Nonnull final InjectionType type, @Nonnull final E value) {
    final TaintedObjects to = ctx.getTaintedObjects();
    final TaintedObject taintedObject = to.get(value);
    if (taintedObject == null) {
      return null;
    }
    Range[] ranges = Ranges.getNotMarkedRanges(taintedObject.getRanges(), type.mark());
    if (ranges == null || ranges.length == 0) {
      return null;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return null;
    }
    final Evidence result = buildEvidence(value, ranges);
    report(span, type, result);
    return result;
  }

  protected final <E> @Nullable Evidence checkInjectionDeeply(
      @Nonnull final IastContext ctx, @Nonnull final InjectionType type, @Nonnull final E value) {
    final InjectionVisitor visitor = new InjectionVisitor(ctx, type);
    ObjectVisitor.visit(value, visitor);
    return visitor.evidence;
  }

  protected final <E> @Nullable Evidence checkInjection(
      @Nonnull final InjectionType type, @Nonnull final RangesProvider<E> rangeProvider) {
    final int rangeCount = rangeProvider.rangeCount();
    if (rangeCount == 0) {
      return null;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return null;
    }
    String evidence;
    Range[] targetRanges;
    if (rangeProvider.size() == 1) {
      // only one item and has ranges
      final E item = rangeProvider.value(0);
      if (item == null) {
        return null; // should never happen
      }
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

    final Evidence result = buildEvidence(evidence, notMarkedRanges);
    report(span, type, result);
    return result;
  }

  protected final <E> @Nullable Evidence checkInjection(
      @Nonnull final InjectionType type, @Nonnull final RangesProvider<E>... rangeProviders) {
    int rangeCount = 0;
    for (final RangesProvider<E> provider : rangeProviders) {
      rangeCount += provider.rangeCount();
    }
    if (rangeCount == 0) {
      return null;
    }
    final AgentSpan span = AgentTracer.activeSpan();
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
    final Evidence result = buildEvidence(evidence, notMarkedRanges);
    report(span, type, result);
    return result;
  }

  protected final void report(
      @Nullable final AgentSpan span,
      @Nonnull final VulnerabilityType type,
      @Nonnull final Evidence evidence) {
    reporter.report(
        span,
        new Vulnerability(type, Location.forSpanAndStack(span, getCurrentStackTrace()), evidence));
  }

  protected StackTraceElement getCurrentStackTrace() {
    return stackWalker.walk(SinkModuleBase::findValidPackageForVulnerability);
  }

  protected Evidence buildEvidence(final Object value, final Range[] ranges) {
    final Range unbound = Ranges.findUnbound(ranges);
    if (unbound != null) {
      final Source source = unbound.getSource();
      if (source != null && source.getValue() != null) {
        final String sourceValue = source.getValue();
        final String evidenceValue = value.toString();
        final int start = evidenceValue.indexOf(sourceValue);
        if (start >= 0) {
          return new Evidence(
              evidenceValue,
              new Range[] {new Range(start, sourceValue.length(), source, unbound.getMarks())});
        }
      }
    }
    return new Evidence(value instanceof String ? (String) value : value.toString(), ranges);
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

  private class InjectionVisitor implements Visitor {

    private final IastContext ctx;
    private final InjectionType type;
    @Nullable private Evidence evidence;

    private InjectionVisitor(final IastContext ctx, final InjectionType type) {
      this.ctx = ctx;
      this.type = type;
    }

    @Nonnull
    @Override
    public State visit(@Nonnull final String path, @Nonnull final Object value) {
      evidence = checkInjection(ctx, type, value);
      return evidence != null ? EXIT : CONTINUE; // report first tainted value only
    }
  }
}
