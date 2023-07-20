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
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie;
import datadog.trace.util.stacktrace.StackWalker;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Base class with utility methods for with sinks */
public abstract class SinkModuleBase implements HasDependencies {
  private static final int MAX_VISITED_OBJECTS = 1000;
  private static final int MAX_RECURSIVE_DEPTH = 10;
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

  protected Object isDeeplyTainted(
      @Nonnull final Object value, @Nonnull final TaintedObjects taintedObjects) {
    return isDeeplyTaintedRecursive(value, taintedObjects, new HashSet<>(), MAX_RECURSIVE_DEPTH);
  }

  private Object isDeeplyTaintedRecursive(
      @Nonnull final Object value,
      @Nonnull final TaintedObjects taintedObjects,
      Set<Object> visitedObjects,
      int depth) {
    if (null == value) {
      return null;
    }
    if (visitedObjects.size() > MAX_VISITED_OBJECTS) {
      return null;
    }
    if (visitedObjects.contains(value)) {
      return null;
    }
    TaintedObject taintedObject = taintedObjects.get(value);
    if (null != taintedObject) {
      return value;
    } else {
      if (depth <= 0) {
        return null;
      }
      visitedObjects.add(value);
      if (value instanceof Object[]) {
        Object[] array = (Object[]) value;
        for (int i = 0; i < array.length; i++) {
          Object arrayValue = array[i];
          Object result =
              isDeeplyTaintedRecursive(arrayValue, taintedObjects, visitedObjects, depth - 1);
          if (null != result) {
            return result;
          } else {
            visitedObjects.add(arrayValue);
          }
        }
        return null;
      } else if (value instanceof Map) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          Object result =
              isDeeplyTaintedRecursive(entry.getKey(), taintedObjects, visitedObjects, depth);
          if (null != result) {
            return result;
          } else {
            visitedObjects.add(entry.getKey());
          }
          result =
              isDeeplyTaintedRecursive(entry.getValue(), taintedObjects, visitedObjects, depth - 1);
          if (null != result) {
            return result;
          } else {
            visitedObjects.add(entry.getValue());
          }
        }
        return null;
      } else if (value instanceof Collection) {
        for (Object object : (Collection<?>) value) {
          Object result =
              isDeeplyTaintedRecursive(object, taintedObjects, visitedObjects, depth - 1);
          if (null != result) {
            return result;
          } else {
            visitedObjects.add(object);
          }
        }
        return null;
      } else {
        return null;
      }
    }
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
