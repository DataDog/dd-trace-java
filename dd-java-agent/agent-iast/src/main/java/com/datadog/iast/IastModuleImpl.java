package com.datadog.iast;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.SourceType;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalker;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class IastModuleImpl implements IastModule {

  private static final int NULL_STR_LENGTH = "null".length();
  private static final Object[] EMPTY = new Object[0];

  private static final Pattern FORMAT_PATTERN =
      Pattern.compile("%(\\d+\\$)?([-#+ 0,(\\<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

  private final Config config;
  private final Reporter reporter;
  private final OverheadController overheadController;
  private final StackWalker stackWalker = StackWalkerFactory.INSTANCE;

  public IastModuleImpl(
      final Config config, final Reporter reporter, final OverheadController overheadController) {
    this.config = config;
    this.reporter = reporter;
    this.overheadController = overheadController;
  }

  public void onCipherAlgorithm(@Nullable final String algorithm) {
    if (algorithm == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakCipherAlgorithms().matcher(algorithmId).matches()) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    // get StackTraceElement for the callee of MessageDigest
    StackTraceElement stackTraceElement =
        stackWalker.walk(
            stack ->
                stack
                    .filter(s -> !s.getClassName().equals("javax.crypto.Cipher"))
                    .findFirst()
                    .get());

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_CIPHER,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(algorithm));
    reporter.report(span, vulnerability);
  }

  public void onHashingAlgorithm(@Nullable final String algorithm) {
    if (algorithm == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakHashAlgorithms().contains(algorithmId)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    // get StackTraceElement for the caller of MessageDigest
    StackTraceElement stackTraceElement =
        stackWalker.walk(
            stack ->
                stack
                    .filter(s -> !s.getClassName().equals("java.security.MessageDigest"))
                    .findFirst()
                    .get());

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_HASH,
            Location.forSpanAndStack(span.getSpanId(), stackTraceElement),
            new Evidence(algorithm));
    reporter.report(span, vulnerability);
  }

  @Override
  public void onParameterName(@Nullable final String paramName) {
    if (paramName == null || paramName.isEmpty()) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        paramName, new Source(SourceType.REQUEST_PARAMETER_NAME, paramName, null));
  }

  @Override
  public void onParameterValue(
      @Nullable final String paramName, @Nullable final String paramValue) {
    if (paramValue == null || paramValue.isEmpty()) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    taintedObjects.taintInputString(
        paramValue, new Source(SourceType.REQUEST_PARAMETER_VALUE, paramName, paramValue));
  }

  @Override
  public void onStringConcat(
      @Nullable final String left, @Nullable final String right, @Nullable final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    if (!canBeTainted(left) && !canBeTainted(right)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final Range[] rangesLeft = getRanges(taintedObjects, left);
    final Range[] rangesRight = getRanges(taintedObjects, right);
    if (rangesLeft.length == 0 && rangesRight.length == 0) {
      return;
    }
    final Range[] ranges =
        mergeRanges(left == null ? NULL_STR_LENGTH : left.length(), rangesLeft, rangesRight);
    taintedObjects.taint(result, ranges);
  }

  @Override
  public void onStringConstructor(@Nullable CharSequence argument, @Nonnull String result) {
    if (!canBeTainted(argument)) {
      return;
    }

    IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    TaintedObjects taintedObjects = ctx.getTaintedObjects();
    Range[] ranges = getRanges(taintedObjects, argument);
    if (ranges.length == 0) {
      return;
    }

    taintedObjects.taint(result, ranges);
  }

  @Override
  public void onStringBuilderAppend(
      @Nullable final StringBuilder builder, @Nullable final CharSequence param) {
    if (!canBeTainted(builder) || !canBeTainted(param)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject paramTainted = taintedObjects.get(param);
    if (paramTainted == null) {
      return;
    }
    final Range[] rangesRight = paramTainted.getRanges();
    final Range[] rangesLeft = getRanges(taintedObjects, builder);
    if (rangesLeft.length == 0 && rangesRight.length == 0) {
      return;
    }
    final Range[] ranges = mergeRanges(builder.length() - param.length(), rangesLeft, rangesRight);
    taintedObjects.taint(builder, ranges);
  }

  @Override
  public void onStringBuilderToString(
      @Nullable final StringBuilder builder, @Nullable final String result) {
    if (!canBeTainted(builder) || !canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject to = taintedObjects.get(builder);
    if (to == null) {
      return;
    }
    taintedObjects.taint(result, to.getRanges());
  }

  private static Range[] getRanges(final TaintedObject taintedObject) {
    return taintedObject == null ? Ranges.EMPTY : taintedObject.getRanges();
  }

  private static Range[] getRanges(
      @Nonnull final TaintedObjects taintedObjects, @Nullable final Object target) {
    if (target == null) {
      return Ranges.EMPTY;
    }
    return getRanges(taintedObjects.get(target));
  }

  private static boolean canBeTainted(@Nullable final CharSequence s) {
    return s != null && s.length() > 0;
  }

  private static Range[] mergeRanges(
      final int offset, @Nonnull final Range[] rangesLeft, @Nonnull final Range[] rangesRight) {
    final int nRanges = rangesLeft.length + rangesRight.length;
    final Range[] ranges = new Range[nRanges];
    if (rangesLeft.length > 0) {
      System.arraycopy(rangesLeft, 0, ranges, 0, rangesLeft.length);
    }
    if (rangesRight.length > 0) {
      Ranges.copyShift(rangesRight, ranges, rangesLeft.length, offset);
    }
    return ranges;
  }
}
