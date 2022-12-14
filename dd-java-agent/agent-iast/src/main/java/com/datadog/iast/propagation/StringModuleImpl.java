package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Ranges.mergeRanges;
import static com.datadog.iast.taint.Tainteds.canBeTainted;
import static com.datadog.iast.taint.Tainteds.getTainted;

import com.datadog.iast.IastModuleBase;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.propagation.StringModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class StringModuleImpl extends IastModuleBase implements StringModule {

  private static final int NULL_STR_LENGTH = "null".length();

  @Override
  public void onStringConcat(
      @Nonnull final String left, @Nullable final String right, @Nonnull final String result) {
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
    final TaintedObject taintedLeft = getTainted(taintedObjects, left);
    final TaintedObject taintedRight = getTainted(taintedObjects, right);
    if (taintedLeft == null && taintedRight == null) {
      return;
    }
    final Range[] ranges;
    if (taintedRight == null) {
      ranges = taintedLeft.getRanges();
    } else if (taintedLeft == null) {
      ranges = new Range[taintedRight.getRanges().length];
      Ranges.copyShift(taintedRight.getRanges(), ranges, 0, left.length());
    } else {
      ranges = mergeRanges(left.length(), taintedLeft.getRanges(), taintedRight.getRanges());
    }
    taintedObjects.taint(result, ranges);
  }

  @Override
  public void onStringBuilderInit(
      @Nonnull final StringBuilder builder, @Nullable final CharSequence param) {
    if (!canBeTainted(param)) {
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
    taintedObjects.taint(builder, paramTainted.getRanges());
  }

  @Override
  public void onStringBuilderAppend(
      @Nonnull final StringBuilder builder, @Nullable final CharSequence param) {
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
    final TaintedObject builderTainted = taintedObjects.get(builder);
    final int shift = builder.length() - param.length();
    if (builderTainted == null) {
      final Range[] paramRanges = paramTainted.getRanges();
      final Range[] ranges = new Range[paramRanges.length];
      Ranges.copyShift(paramRanges, ranges, 0, shift);
      taintedObjects.taint(builder, ranges);
    } else {
      final Range[] builderRanges = builderTainted.getRanges();
      final Range[] paramRanges = paramTainted.getRanges();
      final Range[] ranges = mergeRanges(shift, builderRanges, paramRanges);
      builderTainted.setRanges(ranges);
    }
  }

  @Override
  public void onStringBuilderToString(
      @Nonnull final StringBuilder builder, @Nonnull final String result) {
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

  @Override
  public void onStringConcatFactory(
      @Nullable final String result,
      @Nullable final String[] args,
      @Nullable final String recipe,
      @Nullable final Object[] constants,
      @Nonnull final int[] recipeOffsets) {
    if (!canBeTainted(result) || !canBeTainted(args)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }

    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final Map<Integer, Range[]> sourceRanges = new HashMap<>();
    int rangeCount = 0;
    for (int i = 0; i < args.length; i++) {
      final TaintedObject to = getTainted(taintedObjects, args[i]);
      if (to != null) {
        final Range[] ranges = to.getRanges();
        sourceRanges.put(i, ranges);
        rangeCount += ranges.length;
      }
    }
    if (rangeCount == 0) {
      return;
    }

    final Range[] targetRanges = new Range[rangeCount];
    int offset = 0, rangeIndex = 0;
    for (int item : recipeOffsets) {
      if (item < 0) {
        offset += (-item);
      } else {
        final String argument = args[item];
        final Range[] ranges = sourceRanges.get(item);
        if (ranges != null) {
          Ranges.copyShift(ranges, targetRanges, rangeIndex, offset);
          rangeIndex += ranges.length;
        }
        offset += getToStringLength(argument);
      }
    }
    taintedObjects.taint(result, targetRanges);
  }

  @Override
  @SuppressFBWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
  public void onStringSubSequence(
      @Nonnull String self, int beginIndex, int endIndex, @Nullable CharSequence result) {
    if (self == result || !canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject selfTainted = taintedObjects.get(self);
    if (selfTainted == null) {
      return;
    }
    final Range[] rangesSelf = selfTainted.getRanges();
    if (rangesSelf.length == 0) {
      return;
    }
    Range[] newRanges = Ranges.forSubstring(beginIndex, result.length(), rangesSelf);
    if (newRanges != null && newRanges.length > 0) {
      taintedObjects.taint(result, newRanges);
    }
  }

  @Override
  public void onStringJoin(
      @Nullable String result, @Nonnull CharSequence delimiter, @Nonnull CharSequence... elements) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    // String.join may internally call StringJoiner, if StringJoiner did the job don't do it twice
    if (getTainted(taintedObjects, result) != null) {
      return;
    }
    List<Range> newRanges = new ArrayList<>();
    int pos = 0;
    // Delimiter info
    Range[] delimiterRanges = getRanges(getTainted(taintedObjects, delimiter));
    boolean delimiterHasRanges = delimiterRanges.length > 0;
    int delimiterLength = delimiter.length();

    for (int i = 0; i < elements.length; i++) {
      CharSequence element = elements[i];
      pos =
          getPositionAndUpdateRangesInStringJoin(
              taintedObjects,
              newRanges,
              pos,
              delimiterRanges,
              delimiterLength,
              element,
              delimiterHasRanges && i < elements.length - 1);
    }
    if (!newRanges.isEmpty()) {
      taintedObjects.taint(result, newRanges.toArray(new Range[0]));
    }
  }

  private static int getToStringLength(@Nullable final CharSequence s) {
    return s == null ? NULL_STR_LENGTH : s.length();
  }

  /**
   * Iterates over the element and delimiter ranges (if necessary) to update them and calculate the
   * new pos value
   */
  private static int getPositionAndUpdateRangesInStringJoin(
      TaintedObjects taintedObjects,
      List<Range> newRanges,
      int pos,
      Range[] delimiterRanges,
      int delimiterLength,
      CharSequence element,
      boolean addDelimiterRanges) {
    if (canBeTainted(element)) {
      TaintedObject elementTainted = taintedObjects.get(element);
      if (elementTainted != null) {
        Range[] elementRanges = elementTainted.getRanges();
        if (elementRanges.length > 0) {
          for (Range range : elementRanges) {
            newRanges.add(pos == 0 ? range : range.shift(pos));
          }
        }
      }
    }
    pos += getToStringLength(element);
    if (addDelimiterRanges) {
      for (Range range : delimiterRanges) {
        newRanges.add(range.shift(pos));
      }
    }
    pos += delimiterLength;
    return pos;
  }

  private static Range[] getRanges(final TaintedObject taintedObject) {
    return taintedObject == null ? Ranges.EMPTY : taintedObject.getRanges();
  }
}
