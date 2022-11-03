package com.datadog.iast;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.SourceType;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastModule;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class IastModuleImpl implements IastModule {

  private static final int NULL_STR_LENGTH = "null".length();

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
  public void onStringConcatFactory(
      @Nullable final String[] args,
      @Nullable final String result,
      @Nullable final String recipe,
      @Nullable final Object[] constants,
      @Nonnull final int[] recipeOffsets) {
    if (!canBeTaintedNullSafe(result) || !canBeTaintedNullSafe(args)) {
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

  private static TaintedObject getTainted(final TaintedObjects to, final Object value) {
    return value == null ? null : to.get(value);
  }

  private static boolean canBeTainted(@Nonnull final CharSequence s) {
    return s.length() > 0;
  }

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence s) {
    return s != null && canBeTainted(s);
  }

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence[] args) {
    if (args == null || args.length == 0) {
      return false;
    }
    for (final CharSequence item : args) {
      if (canBeTaintedNullSafe(item)) {
        return true;
      }
    }
    return false;
  }

  private static int getToStringLength(@Nullable final String s) {
    return s == null ? NULL_STR_LENGTH : s.length();
  }
}
