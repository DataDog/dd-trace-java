package com.datadog.iast.sink;

import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static com.datadog.iast.util.ObjectVisitor.State.EXIT;
import static datadog.trace.api.iast.VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK;

import com.datadog.iast.Dependencies;
import com.datadog.iast.Reporter;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.ObjectVisitor;
import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie;
import datadog.trace.instrumentation.iastinstrumenter.SourceMapperImpl;
import datadog.trace.util.stacktrace.StackWalker;
import java.util.Iterator;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

/** Base class with utility methods for with sinks */
@SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
public abstract class SinkModuleBase {

  private static final int MAX_EVIDENCE_LENGTH = Config.get().getIastTruncationMaxValueLength();

  protected final OverheadController overheadController;
  protected final Reporter reporter;
  protected final StackWalker stackWalker;

  protected SinkModuleBase(@Nonnull final Dependencies dependencies) {
    overheadController = dependencies.getOverheadController();
    reporter = dependencies.getReporter();
    stackWalker = dependencies.getStackWalker();
  }

  protected void report(final Vulnerability vulnerability) {
    report(AgentTracer.activeSpan(), vulnerability);
  }

  protected void report(@Nullable final AgentSpan span, final Vulnerability vulnerability) {
    if (!overheadController.consumeQuota(
        Operations.REPORT_VULNERABILITY, span, vulnerability.getType())) {
      return;
    }
    reporter.report(span, vulnerability);
  }

  protected void report(final VulnerabilityType type, final Evidence evidence) {
    report(AgentTracer.activeSpan(), type, evidence);
  }

  protected void report(
      @Nullable final AgentSpan span, final VulnerabilityType type, final Evidence evidence) {
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span, type)) {
      return;
    }
    final Vulnerability vulnerability =
        new Vulnerability(type, buildLocation(span, null), evidence);
    reporter.report(span, vulnerability);
  }

  @Nullable
  protected final Evidence checkInjection(final VulnerabilityType type, final Object value) {
    return checkInjection(type, value, null, null);
  }

  @Nullable
  protected final Evidence checkInjection(
      final VulnerabilityType type, final Object value, final LocationSupplier locationSupplier) {
    return checkInjection(type, value, null, locationSupplier);
  }

  @Nullable
  protected final Evidence checkInjection(
      final VulnerabilityType type, final Object value, final EvidenceBuilder evidenceBuilder) {
    return checkInjection(type, value, evidenceBuilder, null);
  }

  @Nullable
  protected final Evidence checkInjection(
      final VulnerabilityType type,
      final Object value,
      @Nullable final EvidenceBuilder evidenceBuilder,
      @Nullable final LocationSupplier locationSupplier) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return null;
    }

    return checkInjection(ctx, type, value, evidenceBuilder, locationSupplier);
  }

  @Nullable
  protected final Evidence checkInjection(
      final IastContext ctx,
      final VulnerabilityType type,
      Object value,
      @Nullable final EvidenceBuilder evidenceBuilder,
      @Nullable final LocationSupplier locationSupplier) {

    final TaintedObjects to = ctx.getTaintedObjects();
    final Range[] valueRanges;
    if (value instanceof Taintable) {
      final Taintable taintable = (Taintable) value;
      if (!taintable.$DD$isTainted()) {
        return null;
      }
      final Source source = (Source) taintable.$$DD$getSource();
      final Object origin = source.getRawValue();
      final TaintedObject tainted = origin == null ? null : to.get(origin);
      if (origin != null && tainted != null) {
        valueRanges = Ranges.getNotMarkedRanges(tainted.getRanges(), type.mark());
        addSecurityControlMetrics(ctx, valueRanges, tainted.getRanges(), type);
        value = origin;
      } else {
        valueRanges = Ranges.forObject((Source) taintable.$$DD$getSource(), type.mark());
        value = String.format("Tainted reference detected in " + value.getClass());
      }
    } else {
      final TaintedObject tainted = to.get(value);
      if (tainted == null) {
        return null;
      }
      valueRanges = Ranges.getNotMarkedRanges(tainted.getRanges(), type.mark());
      addSecurityControlMetrics(ctx, valueRanges, tainted.getRanges(), type);
    }

    if (valueRanges == null || valueRanges.length == 0) {
      return null;
    }

    // filter excluded ranges
    final Range[] filteredRanges;
    if (!type.excludedSources().isEmpty()) {
      filteredRanges = Ranges.excludeRangesBySource(valueRanges, type.excludedSources());
    } else {
      filteredRanges = valueRanges;
    }

    if (filteredRanges == null || filteredRanges.length == 0) {
      return null;
    }

    final StringBuilder evidence = new StringBuilder();
    final RangeBuilder ranges = new RangeBuilder();
    addToEvidence(type, evidence, ranges, value, filteredRanges, evidenceBuilder);

    // check if finally we have an injection
    if (ranges.isEmpty()) {
      return null;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span, type)) {
      return null;
    }

    return report(span, type, evidence, ranges, locationSupplier);
  }

  private void addSecurityControlMetrics(
      @Nonnull final IastContext ctx,
      @Nullable final Range[] valueRanges,
      @Nonnull final Range[] taintedRanges,
      @Nonnull final VulnerabilityType type) {
    if ((valueRanges != null
            && valueRanges.length
                != 0) // ranges without the vulnerability mark implies vulnerability
        || taintedRanges.length == 0 // no tainted ranges
    ) {
      return;
    }
    // check if there are tainted ranges without the security control mark
    Range[] marked = Ranges.getNotMarkedRanges(taintedRanges, CUSTOM_SECURITY_CONTROL_MARK);
    if (marked == null || marked.length == 0) {
      IastMetricCollector.add(IastMetric.SUPPRESSED_VULNERABILITIES, type.type(), 1, ctx);
    }
  }

  @Nullable
  protected final Evidence checkInjection(final VulnerabilityType type, final Iterator<?> items) {
    return checkInjection(type, items, null, null);
  }

  @Nullable
  protected final Evidence checkInjection(
      final VulnerabilityType type,
      final Iterator<?> items,
      final LocationSupplier locationSupplier) {
    return checkInjection(type, items, null, locationSupplier);
  }

  @Nullable
  protected final Evidence checkInjection(
      final VulnerabilityType type,
      final Iterator<?> items,
      final EvidenceBuilder evidenceBuilder) {
    return checkInjection(type, items, evidenceBuilder, null);
  }

  @Nullable
  protected final Evidence checkInjection(
      final VulnerabilityType type,
      final Iterator<?> items,
      @Nullable final EvidenceBuilder evidenceBuilder,
      @Nullable final LocationSupplier locationSupplier) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return null;
    }

    final TaintedObjects to = ctx.getTaintedObjects();
    final StringBuilder evidence = new StringBuilder();
    final RangeBuilder ranges = new RangeBuilder();
    boolean spanFetched = false;
    AgentSpan span = null;
    while (items.hasNext()) {
      final Object value = items.next();
      if (value == null) {
        continue;
      }

      final TaintedObject tainted = to.get(value);
      Range[] valueRanges = null;
      if (tainted != null) {
        valueRanges = Ranges.getNotMarkedRanges(tainted.getRanges(), type.mark());
        addSecurityControlMetrics(ctx, valueRanges, tainted.getRanges(), type);
      }
      addToEvidence(type, evidence, ranges, value, valueRanges, evidenceBuilder);

      // in case we have an injection let's check if we can report it and exit early if not
      if (!spanFetched && valueRanges != null && valueRanges.length > 0) {
        span = AgentTracer.activeSpan();
        spanFetched = true;
        if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span, type)) {
          return null;
        }
      }

      if (ranges.isFull() || evidence.length() >= MAX_EVIDENCE_LENGTH) {
        break;
      }
    }
    return report(span, type, evidence, ranges, locationSupplier);
  }

  @Nullable
  protected Evidence checkInjectionDeeply(
      final VulnerabilityType type, final Object value, final Predicate<Class<?>> filter) {
    return checkInjectionDeeply(type, value, filter, null, null);
  }

  @Nullable
  @SuppressWarnings("unused")
  protected Evidence checkInjectionDeeply(
      final VulnerabilityType type,
      final Object value,
      final Predicate<Class<?>> filter,
      @Nullable final EvidenceBuilder evidenceBuilder) {
    return checkInjectionDeeply(type, value, filter, evidenceBuilder, null);
  }

  @Nullable
  @SuppressWarnings("unused")
  protected Evidence checkInjectionDeeply(
      final VulnerabilityType type,
      final Object value,
      final Predicate<Class<?>> filter,
      @Nullable final LocationSupplier locationSupplier) {
    return checkInjectionDeeply(type, value, filter, null, locationSupplier);
  }

  @Nullable
  protected Evidence checkInjectionDeeply(
      final VulnerabilityType type,
      final Object value,
      final Predicate<Class<?>> filter,
      @Nullable final EvidenceBuilder evidenceBuilder,
      @Nullable final LocationSupplier locationSupplier) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return null;
    }

    final InjectionVisitor visitor =
        new InjectionVisitor(ctx, type, evidenceBuilder, locationSupplier);
    ObjectVisitor.visit(value, visitor, filter);
    return visitor.evidence;
  }

  @Nullable
  private Evidence report(
      @Nullable final AgentSpan span,
      final VulnerabilityType type,
      final StringBuilder evidenceString,
      final RangeBuilder ranges,
      @Nullable final LocationSupplier locationSupplier) {
    if (ranges.isEmpty()) {
      return null;
    }
    final Evidence evidence = new Evidence(evidenceString.toString(), ranges.toArray());
    final Location location = buildLocation(span, locationSupplier);
    final Vulnerability vulnerability = new Vulnerability(type, location, evidence);
    reporter.report(span, vulnerability);
    return evidence;
  }

  protected void addToEvidence(
      final VulnerabilityType type,
      final StringBuilder evidence,
      final RangeBuilder ranges,
      final Object value,
      @Nullable final Range[] valueRanges,
      @Nullable final EvidenceBuilder evidenceBuilder) {
    if (evidenceBuilder != null) {
      if (isTainted(valueRanges)) {
        evidenceBuilder.tainted(evidence, ranges, value, valueRanges);
      } else {
        evidenceBuilder.nonTainted(evidence, value);
      }
    } else {
      int offset = evidence.length();
      if (offset > 0) {
        evidence.append(type.separator());
        offset++;
      }
      evidence.append(value);
      if (isTainted(valueRanges)) {
        final Range unbound = Ranges.findUnbound(valueRanges);
        if (unbound != null) {
          // use a single range covering the whole value for unbound items
          final Source source = unbound.getSource();
          ranges.add(new Range(offset, evidence.length() - offset, source, unbound.getMarks()));
        } else {
          ranges.add(valueRanges, offset);
        }
      }
    }
  }

  protected Location buildLocation(
      @Nullable final AgentSpan span, @Nullable final LocationSupplier supplier) {
    if (supplier != null) {
      return supplier.build(span);
    }
    return Location.forSpanAndStack(span, getCurrentStackTrace());
  }

  protected final StackTraceElement getCurrentStackTrace() {
    StackTraceElement stackTraceElement =
        stackWalker.walk(SinkModuleBase::findValidPackageForVulnerability);
    // If the source mapper is enabled, we should try to map the stack trace element to the original
    // source file
    if (SourceMapperImpl.INSTANCE != null) {
      Pair<String, Integer> pair =
          SourceMapperImpl.INSTANCE.getFileAndLine(
              stackTraceElement.getClassName(), stackTraceElement.getLineNumber());
      if (pair != null && pair.getLeft() != null && pair.getRight() != null) {
        return new StackTraceElement(
            pair.getLeft(), stackTraceElement.getMethodName(), pair.getLeft(), pair.getRight());
      }
    }
    return stackTraceElement;
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

  @Contract("null -> false")
  private static boolean isTainted(@Nullable final Range[] ranges) {
    return ranges != null && ranges.length > 0;
  }

  /**
   * Building the location of a vulnerability might be expensive (e.g. when computing the call stack
   * to traverse until customer code), this interface makes the construction of the location a lazy
   * op that only happens when a vulnerability has been discovered.
   */
  public interface LocationSupplier {
    Location build(@Nullable AgentSpan span);
  }

  /** Builder instance to construct the final evidence of a vulnerability */
  public interface EvidenceBuilder {
    void tainted(StringBuilder evidence, RangeBuilder ranges, Object value, Range[] valueRanges);

    default void nonTainted(StringBuilder evidence, Object value) {
      // do nothing by default if the value is not tainted
    }
  }

  private class InjectionVisitor implements ObjectVisitor.Visitor {

    private final IastContext ctx;
    private final VulnerabilityType type;
    @Nullable private final EvidenceBuilder evidenceBuilder;
    @Nullable private final LocationSupplier locationSupplier;
    @Nullable private Evidence evidence;

    private InjectionVisitor(
        final IastContext ctx,
        final VulnerabilityType type,
        @Nullable final EvidenceBuilder evidenceBuilder,
        @Nullable final LocationSupplier locationSupplier) {
      this.ctx = ctx;
      this.type = type;
      this.evidenceBuilder = evidenceBuilder;
      this.locationSupplier = locationSupplier;
    }

    @Nonnull
    @Override
    public ObjectVisitor.State visit(@Nonnull final String path, @Nonnull final Object value) {
      evidence = checkInjection(ctx, type, value, evidenceBuilder, locationSupplier);
      return evidence != null ? EXIT : CONTINUE; // report first tainted value only
    }
  }
}
