package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Ranges.EMPTY;
import static com.datadog.iast.taint.Ranges.highestPriorityRange;
import static com.datadog.iast.taint.Ranges.mergeRanges;
import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;
import static com.datadog.iast.taint.Tainteds.getTainted;

import com.datadog.iast.model.Range;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.Ranges.RangeList;
import com.datadog.iast.taint.Ranges.RangesProvider;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.Ranged;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.StringModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class StringModuleImpl implements StringModule {

  /** {@link java.util.Formatter#formatSpecifier} */
  private static final Pattern FORMAT_PATTERN =
      Pattern.compile("%(?<index>\\d+\\$)?([-#+ 0,(\\<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");

  private static final Ranged END = Ranged.build(Integer.MAX_VALUE, 0);

  private static final int NULL_STR_LENGTH = "null".length();

  @SuppressWarnings("NullAway") // NullAway fails with taintedLeft and taintedRight checks
  @Override
  public void onStringConcat(
      @Nonnull final String left, @Nullable final String right, @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    if (!canBeTainted(left) && !canBeTainted(right)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
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
      @Nonnull final CharSequence builder, @Nullable final CharSequence param) {
    if (!canBeTainted(param)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
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
      @Nonnull final CharSequence builder, @Nullable final CharSequence param) {
    if (!canBeTainted(builder) || !canBeTainted(param)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
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
      @Nonnull final CharSequence builder, @Nonnull final String result) {
    if (!canBeTainted(builder) || !canBeTainted(result)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
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
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }

    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final Map<Integer, Range[]> sourceRanges = new HashMap<>();
    long rangeCount = 0;
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

    final Range[] targetRanges = Ranges.newArray(rangeCount);
    int offset = 0, rangeIndex = 0;
    for (int item : recipeOffsets) {
      if (item < 0) {
        offset += -item;
      } else {
        final String argument = args[item];
        final Range[] ranges = sourceRanges.get(item);
        if (ranges != null) {
          rangeIndex = insertRange(targetRanges, ranges, offset, rangeIndex);
          if (rangeIndex >= targetRanges.length) {
            break;
          }
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
    final IastContext ctx = IastContext.Provider.get();
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
      @Nullable String result, @Nonnull CharSequence delimiter, @Nonnull CharSequence[] elements) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    // String.join may internally call StringJoiner, if StringJoiner did the job don't do it twice
    if (getTainted(taintedObjects, result) != null) {
      return;
    }
    final RangesProvider<CharSequence> elementRanges = rangesProviderFor(taintedObjects, elements);
    final Range[] delimiterRanges = getRanges(getTainted(taintedObjects, delimiter));
    final long rangeCount =
        elementRanges.rangeCount() + ((long) (elements.length - 1) * delimiterRanges.length);
    if (rangeCount == 0) {
      return;
    }
    final Range[] targetRanges = Ranges.newArray(rangeCount);
    int delimiterLength = getToStringLength(delimiter), offset = 0, rangeIndex = 0;
    for (int i = 0; i < elements.length; i++) {
      // insert element ranges
      final CharSequence element = elements[i];
      final Range[] ranges = elementRanges.ranges(element);
      if (ranges != null) {
        rangeIndex = insertRange(targetRanges, ranges, offset, rangeIndex);
        if (rangeIndex >= targetRanges.length) {
          break;
        }
      }
      offset += getToStringLength(element);

      if (i < elements.length - 1) {
        // add delimiter ranges
        rangeIndex = insertRange(targetRanges, delimiterRanges, offset, rangeIndex);
        if (rangeIndex >= targetRanges.length) {
          break;
        }
        offset += delimiterLength;
      }
    }
    taintedObjects.taint(result, targetRanges);
  }

  @Override
  @SuppressFBWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
  public void onStringRepeat(@Nonnull String self, int count, @Nonnull String result) {
    if (!canBeTainted(self) || !canBeTainted(result) || self == result) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final Range[] selfRanges = getRanges(taintedObjects.get(self));
    if (selfRanges.length == 0) {
      return;
    }
    final Range[] ranges = Ranges.newArray(selfRanges.length * (long) count);
    int rangeIndex = 0;
    for (int i = 0; i < count; i++) {
      rangeIndex = insertRange(ranges, selfRanges, i * self.length(), rangeIndex);
      if (rangeIndex >= ranges.length) {
        break;
      }
    }
    taintedObjects.taint(result, ranges);
  }

  private static int getToStringLength(@Nullable final CharSequence s) {
    return s == null ? NULL_STR_LENGTH : s.length();
  }

  @Override
  public void onStringToUpperCase(@Nonnull String self, @Nullable String result) {
    onStringCaseChanged(self, result);
  }

  @Override
  public void onStringToLowerCase(@Nonnull String self, @Nullable String result) {
    onStringCaseChanged(self, result);
  }

  @SuppressFBWarnings
  public void onStringCaseChanged(@Nonnull String self, @Nullable String result) {
    if (!canBeTainted(result)) {
      return;
    }
    if (self == result) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject taintedSelf = taintedObjects.get(self);
    if (taintedSelf == null) {
      return;
    }
    final Range[] rangesSelf = taintedSelf.getRanges();
    if (null == rangesSelf || rangesSelf.length == 0) {
      return;
    }
    if (result.length() == self.length()) {
      taintedObjects.taint(result, rangesSelf);
      return;
    }
    if (result.length() >= self.length()) {
      taintedObjects.taint(result, rangesSelf);
    } // Pathological case where the string's length actually becomes smaller
    else {
      stringCaseChangedWithReducedSize(rangesSelf, taintedObjects, result);
    }
  }

  private void stringCaseChangedWithReducedSize(
      final Range[] rangesSelf, final TaintedObjects taintedObjects, @Nonnull String result) {
    int skippedRanges = 0;
    Range adjustedRange = null;
    for (int i = rangesSelf.length - 1; i >= 0; i--) {
      Range currentRange = rangesSelf[i];
      if (currentRange.getStart() >= result.length()) {
        skippedRanges++;
      } else if (currentRange.getStart() + currentRange.getLength() >= result.length()) {
        adjustedRange =
            Ranges.copyWithPosition(
                currentRange, currentRange.getStart(), result.length() - currentRange.getStart());
      }
    }
    Range[] newRanges = new Range[rangesSelf.length - skippedRanges];
    for (int i = 0; i < newRanges.length; i++) {
      newRanges[i] = rangesSelf[i];
    }
    if (null != adjustedRange) {
      newRanges[newRanges.length - 1] = adjustedRange;
    }
    taintedObjects.taint(result, newRanges);
  }

  /** Inserts the range in the selected position and returns the new position for further ranges */
  private static int insertRange(
      final Range[] targetRanges, final Range[] ranges, final int offset, final int rangeIndex) {
    if (ranges.length == 0) {
      return rangeIndex;
    }
    final int count = Math.min(targetRanges.length - rangeIndex, ranges.length);
    Ranges.copyShift(ranges, targetRanges, rangeIndex, offset, count);
    return rangeIndex + count;
  }

  private static Range[] getRanges(@Nullable final TaintedObject taintedObject) {
    return taintedObject == null ? EMPTY : taintedObject.getRanges();
  }

  @Override
  @SuppressFBWarnings
  public void onStringTrim(@Nonnull final String self, @Nullable final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    if (self == result) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final TaintedObject taintedSelf = taintedObjects.get(self);
    if (taintedSelf == null) {
      return;
    }

    int offset = 0;
    while ((offset < self.length()) && (self.charAt(offset) <= ' ')) {
      offset++;
    }

    int resultLength = result.length();

    final Range[] rangesSelf = taintedSelf.getRanges();
    if (null == rangesSelf || rangesSelf.length == 0) {
      return;
    }

    final Range[] newRanges = Ranges.forSubstring(offset, resultLength, rangesSelf);

    if (null != newRanges) {
      taintedObjects.taint(result, newRanges);
    }
  }

  @Override
  public void onStringConstructor(@Nonnull String self, @Nonnull String result) {
    if (!canBeTainted(self)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects taintedObjects = ctx.getTaintedObjects();
    final Range[] selfRanges = getRanges(taintedObjects.get(self));
    if (selfRanges.length == 0) {
      return;
    }
    taintedObjects.taint(result, selfRanges);
  }

  @Override
  public void onStringFormat(
      @Nonnull final String format, @Nonnull final Object[] params, @Nonnull final String result) {
    onStringFormat(null, format, params, result);
  }

  @Override
  public void onStringFormat(
      @Nullable final Locale locale,
      @Nonnull final String format,
      @Nonnull final Object[] parameters,
      @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<Object> paramRangesProvider = rangesProviderFor(to, parameters);
    int rangeCount = paramRangesProvider.rangeCount();
    final Deque<Range> formatRanges = new LinkedList<>();
    final TaintedObject formatTainted = to.get(format);
    if (formatTainted != null) {
      rangeCount += formatTainted.getRanges().length;
      formatRanges.addAll(Arrays.asList(formatTainted.getRanges()));
    }
    if (rangeCount == 0) {
      return;
    }
    // params can appear zero or multiple times in the pattern so the final number of ranges is
    // unknown beforehand
    final RangeList finalRanges = new RangeList();
    final Matcher matcher = FORMAT_PATTERN.matcher(format);
    int offset = 0, paramIndex = 0;
    while (matcher.find()) {
      final String placeholder = matcher.group();
      final Object parameter;
      final String formattedValue;
      final String index = matcher.group("index");
      if (index != null) {
        // indexes are 1-based
        final int parsedIndex = Integer.parseInt(index.substring(0, index.length() - 1)) - 1;
        // remove the index before the formatting without increment the current state
        parameter = parameters[parsedIndex];
        formattedValue = String.format(locale, placeholder.replace(index, ""), parameter);
      } else {
        parameter = parameters[paramIndex++];
        formattedValue = String.format(locale, placeholder, parameter);
      }
      final Ranged placeholderPos = Ranged.build(matcher.start(), placeholder.length());
      final Range placeholderRange =
          addFormatTaintedRanges(placeholderPos, offset, formatRanges, finalRanges);
      final Range[] paramRanges = paramRangesProvider.ranges(parameter);
      final int shift = placeholderPos.getStart() + offset;
      addParameterTaintedRanges(
          placeholderRange, parameter, formattedValue, shift, paramRanges, finalRanges);
      offset += (formattedValue.length() - placeholder.length());
      if (finalRanges.isFull()) {
        break;
      }
    }
    addFormatTaintedRanges(
        END, offset, formatRanges, finalRanges); // add remaining ranges from the format
    if (!finalRanges.isEmpty()) {
      to.taint(result, finalRanges.toArray());
    }
  }

  @Override
  public void onStringFormat(
      @Nonnull final Iterable<String> literals,
      @Nonnull final Object[] parameters,
      @Nonnull final String result) {
    if (!canBeTainted(result)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final RangesProvider<Object> paramRangesProvider = rangesProviderFor(to, parameters);
    if (paramRangesProvider.rangeCount() == 0) {
      return;
    }
    // since we might join ranges the final number is unknown beforehand
    final RangeList finalRanges = new RangeList();
    int offset = 0, paramIndex = 0;
    for (final Iterator<String> it = literals.iterator(); it.hasNext(); ) {
      final String literal = it.next();
      offset += literal.length();
      if (it.hasNext() && paramIndex < parameters.length) {
        final Object parameter = parameters[paramIndex++];
        final Range[] parameterRanges = paramRangesProvider.ranges(parameter);
        final String formatted = String.valueOf(parameter);
        addParameterTaintedRanges(null, parameter, formatted, offset, parameterRanges, finalRanges);
        offset += formatted.length();
      }
      if (finalRanges.isFull()) {
        break;
      }
    }
    if (!finalRanges.isEmpty()) {
      to.taint(result, finalRanges.toArray());
    }
  }

  @Override
  @SuppressFBWarnings
  public void onSplit(@Nonnull String self, @Nonnull String[] result) {
    if (!canBeTainted(self) || !canBeTainted(result)) {
      return;
    }
    if (result.length == 1 && result[0] == self) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    TaintedObject taintedString = to.get(self);
    if (taintedString == null) {
      return;
    }
    Range priorityRange = highestPriorityRange(taintedString.getRanges());
    for (String s : result) {
      to.taint(s, new Range[] {Ranges.copyWithPosition(priorityRange, 0, s.length())});
    }
  }

  /**
   * Adds the tainted ranges belonging to the current parameter added via placeholder taking care of
   * an optional tainted placeholder.
   *
   * @param placeholderRange tainted range of the placeholder or {@code null} if not tainted
   * @param param value of the parameter
   * @param formatted parameter as a string
   * @param offset offset from the beginning of the format string
   * @param ranges tainted ranges of the parameter
   * @param finalRanges result with all ranges
   */
  private void addParameterTaintedRanges(
      @Nullable final Range placeholderRange,
      final Object param,
      final String formatted,
      final int offset,
      @Nullable final Range[] ranges,
      /* out */ final RangeList finalRanges) {
    if (ranges != null && ranges.length > 0) {
      // only shift ranges if they are character sequences of the same length, otherwise taint the
      // whole thing
      if (charSequencesOfSameLength(param, formatted)) {
        for (final Range range : ranges) {
          finalRanges.add(range.shift(offset));
        }
      } else {
        finalRanges.add(Ranges.copyWithPosition(ranges[0], offset, formatted.length()));
      }
    } else if (placeholderRange != null) {
      finalRanges.add(Ranges.copyWithPosition(placeholderRange, offset, formatted.length()));
    }
  }

  /**
   * Adds all the ranges contained in the format string before the current placeholder.
   *
   * @param placeholderPos location of the current placeholder
   * @param offset offset from the beginning of the format string
   * @param ranges queue of format string ranges
   * @param finalRanges result with all ranges
   * @return tainted range of the placeholder or {@code null} if not tainted
   */
  @Nullable
  private Range addFormatTaintedRanges(
      final Ranged placeholderPos,
      final int offset,
      final Deque<Range> ranges,
      /* out */ final RangeList finalRanges) {
    Range formatRange;
    int end = placeholderPos.getStart() + placeholderPos.getLength();
    Range placeholderRange = null;
    while ((formatRange = ranges.peek()) != null && formatRange.getStart() < end) {
      ranges.poll();

      // check if the placeholder was tainted
      if (placeholderRange == null) {
        placeholderRange = placeholderPos.intersects(formatRange) ? formatRange : null;
      }

      // 1. remove the placeholder range from the format one
      // 2. append ranges located before tha placeholder
      // 3. enqueue the remaining ones
      for (final Ranged disjoint : formatRange.remove(placeholderPos)) {
        final Range newFormatRange =
            Ranges.copyWithPosition(formatRange, disjoint.getStart(), disjoint.getLength());
        if (newFormatRange.getStart() < placeholderPos.getStart()) {
          finalRanges.add(newFormatRange.shift(offset));
        } else {
          ranges.addFirst(newFormatRange);
        }
      }
    }
    return placeholderRange;
  }

  private boolean charSequencesOfSameLength(final Object param, final CharSequence param2) {
    if (!(param instanceof CharSequence) || param2 == null) {
      return false;
    }
    return ((CharSequence) param).length() == param2.length();
  }
}
