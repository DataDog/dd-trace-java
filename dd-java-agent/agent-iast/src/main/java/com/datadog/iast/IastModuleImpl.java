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
import datadog.trace.api.iast.RealCallThrowable;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalker;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
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

  /** @see String#format(String, Object...) */
  @Override
  public String onStringFormat(@Nullable Locale l, @Nonnull String fmt, @Nullable Object[] args) {
    if (fmt == null) {
      try {
        return String.format(l, fmt, args);
      } catch (Throwable t) {
        throw new RealCallThrowable(t);
      }
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      try {
        return String.format(l, fmt, args);
      } catch (Throwable t) {
        throw new RealCallThrowable(t);
      }
    }

    if (args == null) {
      args = EMPTY;
    }

    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    TaintedObject toFmt = taintedObjects.get(fmt);
    if (toFmt == null) {
      return onStringFormatFmtNotTainted(l, fmt, args, taintedObjects);
    } else {
      return onStringFormatFmtTainted(l, fmt, args, taintedObjects, toFmt);
    }
  }

  private String onStringFormatFmtTainted(
      Locale l, String fmt, Object[] args, TaintedObjects taintedObjects, TaintedObject toFmt) {
    // If the format is tainted, however, things get more complicated.
    // We find the tainted ranges of the format, replace them with %<n>$s,
    // and add new arguments at n.
    // If they include a placeholder, or part of a placeholder, we give up though
    // (we could attempt to resolve the placeholder first, but at that point
    // it's not worth the trouble). We mark the whole result as tainted

    // 1st, find all the patterns
    List<Integer> patternPositions = new ArrayList<>();
    Matcher matcher = FORMAT_PATTERN.matcher(fmt);
    while (matcher.find()) {
      patternPositions.add(matcher.start());
      patternPositions.add(matcher.end());
    }

    // if any range includes a placeholder, we taint the whole thing
    for (Range r : toFmt.getRanges()) {
      for (int i = 0; i < patternPositions.size(); i += 2) {
        if (overlap(
            r.getStart(),
            r.getStart() + r.getLength(),
            patternPositions.get(i),
            patternPositions.get(i + 1),
            null)) {
          String result;
          try {
            result = String.format(l, fmt, args);
          } catch (Throwable t) {
            throw new RealCallThrowable(t);
          }

          taintedObjects.taint(
              result, new Range(0, result.length(), toFmt.getRanges()[0].getSource()));
          return result;
        }
      }
    }

    // if not, add new placeholders for the ranges, extract the tainted portions,
    // taint them, and add them as new arguments
    List<Object> newArgs = new ArrayList<>(Arrays.asList(args));
    String newFmt = fmt;
    int offset = 0; // offset between positions in tainted ranges (rel to orig fmt) and in newFmt
    int posAfterLastInsertedPlaceholder = 0; /* in newFmt */
    StringBuilder sb = new StringBuilder();
    Map<CharSequence, Source> taintedFmtPortions = new IdentityHashMap<>();
    for (Range r : toFmt.getRanges()) {
      int offsetStart = r.getStart() + offset;
      if (offsetStart < posAfterLastInsertedPlaceholder) {
        throw new IllegalStateException("Overlapping ranges in format string");
      }
      String taintedRange = newFmt.substring(offsetStart, offsetStart + r.getLength());
      taintedFmtPortions.put(taintedRange, r.getSource());

      sb.append(newFmt, 0, offsetStart);
      String argNum = Integer.toString(newArgs.size() + 1);
      sb.append('%');
      sb.append(argNum);
      sb.append("$s");
      sb.append(newFmt, offsetStart + r.getLength(), newFmt.length());
      newFmt = sb.toString();
      sb.setLength(0);
      newArgs.add(taintedRange);
      int placeHolderSize = 1 /* % */ + argNum.length() + 2 /* $s */;
      offset += (placeHolderSize - r.getLength());
      posAfterLastInsertedPlaceholder = offsetStart + placeHolderSize;
    }

    TaintedTrackingAppendable tta =
        new TaintedTrackingAppendable(taintedObjects, taintedFmtPortions);
    String result;
    try {
      result = new Formatter(tta, l).format(newFmt, newArgs.toArray()).toString();
    } catch (Throwable t) {
      throw new RealCallThrowable(t);
    }
    if (tta.taintedRanges.size() > 0) {
      taintedObjects.taint(result, tta.taintedRanges.toArray(new Range[tta.taintedRanges.size()]));
    }
    return result;
  }

  private String onStringFormatFmtNotTainted(
      Locale l, String fmt, Object[] args, TaintedObjects taintedObjects) {
    // if the format is not tainted, we can just check for tainted arguments
    if (!isAnyTainted(taintedObjects, args)) {
      try {
        return String.format(l, fmt, args);
      } catch (Throwable t) {
        throw new RealCallThrowable(t);
      }
    }

    TaintedTrackingAppendable tta =
        new TaintedTrackingAppendable(taintedObjects, Collections.emptyMap());
    String result;
    try {
      result = new Formatter(tta, l).format(fmt, args).toString();
    } catch (Throwable t) {
      throw new RealCallThrowable(t);
    }
    if (tta.taintedRanges.size() > 0) {
      taintedObjects.taint(result, tta.taintedRanges.toArray(new Range[tta.taintedRanges.size()]));
    }
    return result;
  }

  private boolean isAnyTainted(TaintedObjects taintedObjects, Object[] args) {
    for (int i = 0; i < args.length; i++) {
      Object o = args[i];
      if (o != null && taintedObjects.get(o) != null) {
        return true;
      }
    }
    return false;
  }

  static class TaintedTrackingAppendable implements Appendable {
    final StringBuffer delegate = new StringBuffer();
    final TaintedObjects tobjs;
    final List<Range> taintedRanges = new ArrayList<>();
    final Map<CharSequence, Source> taintedFmtPortions;

    private TaintedTrackingAppendable(
        TaintedObjects tobjs, Map<CharSequence, Source> taintedFmtPortions) {
      this.tobjs = tobjs;
      this.taintedFmtPortions = taintedFmtPortions;
    }

    @Override
    public Appendable append(CharSequence csq) {
      Source taintedSource = this.taintedFmtPortions.get(csq);
      if (taintedSource != null) {
        int curPos = delegate.length();
        taintedRanges.add(new Range(curPos, csq.length(), taintedSource));
      } else {
        TaintedObject tainted = tobjs.get(csq);
        int curPos = delegate.length();
        if (tainted != null) {
          for (Range r : tainted.getRanges()) {
            taintedRanges.add(new Range(curPos + r.getStart(), r.getLength(), r.getSource()));
          }
        }
      }
      return delegate.append(csq);
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) {
      TaintedObject tainted = tobjs.get(csq);
      int curPos = delegate.length();
      if (tainted != null) {
        for (Range r : tainted.getRanges()) {
          int[] overlap = new int[2];
          if (overlap(start, end, r.getStart(), r.getStart() + r.getLength(), overlap)) {
            taintedRanges.add(
                new Range(curPos + overlap[0], overlap[1] - overlap[0], r.getSource()));
          }
        }
      }
      return delegate.append(csq, start, end);
    }

    @Override
    public Appendable append(char c) {
      return delegate.append(c);
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
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

  private static boolean overlap(
      int start1, int end1 /* excl */, int start2, int end2, int[ /* 2 */] result) {
    if (start1 >= end1 || start2 >= end2) {
      // 0-length ranges
      return false;
    }
    if (start1 >= end2 /* 1 after 2 */ || start2 >= end1 /* 2 after 1 */) {
      return false;
    }

    if (result != null) {
      int ostart = Math.max(start1, start2);
      int oend = Math.min(end1, end2);
      result[0] = ostart;
      result[1] = oend;
    }
    return true;
  }
}
