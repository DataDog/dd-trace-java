package datadog.trace.instrumentation.java.lang;

import static java.lang.invoke.MethodType.methodType;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.google.auto.service.AutoService;
import datadog.trace.api.iast.CallSiteHelper;
import datadog.trace.api.iast.CallSiteHelperContainer;
import datadog.trace.api.iast.RealCallThrowable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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

@AutoService(CallSiteHelperContainer.class)
public class StringHelperContainer implements CallSiteHelperContainer {

  private static final Object[] EMPTY = new Object[0];

  private static final Pattern FORMAT_PATTERN =
      Pattern.compile("%(\\d+\\$)?([-#+ 0,(\\<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

  /** @see String#format(String, Object...) */
  @CallSiteHelper(fallbackMethodHandleProvider = "onStringFormatFallback")
  public static String onStringFormat(
      @Nullable Locale l, @Nonnull String fmt, @Nullable Object[] args) {
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

  public static MethodHandle onStringFormatFallback()
      throws NoSuchMethodException, IllegalAccessException {
    return MethodHandles.lookup()
        .findStatic(
            String.class,
            "format",
            methodType(String.class, Locale.class, String.class, Object[].class));
  }

  private static String onStringFormatFmtTainted(
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

  private static String onStringFormatFmtNotTainted(
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
        new TaintedTrackingAppendable(taintedObjects, Collections.<CharSequence, Source>emptyMap());
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

  private static boolean isAnyTainted(TaintedObjects taintedObjects, Object[] args) {
    for (int i = 0; i < args.length; i++) {
      Object o = args[i];
      if (o != null && taintedObjects.get(o) != null) {
        return true;
      }
    }
    return false;
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
}
