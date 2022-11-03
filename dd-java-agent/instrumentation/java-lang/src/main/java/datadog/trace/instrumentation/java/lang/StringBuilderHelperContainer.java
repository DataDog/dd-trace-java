package datadog.trace.instrumentation.java.lang;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.google.auto.service.AutoService;
import datadog.trace.api.iast.InvokeDynamicHelper;
import datadog.trace.api.iast.InvokeDynamicHelperContainer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@AutoService(InvokeDynamicHelperContainer.class)
public class StringBuilderHelperContainer implements InvokeDynamicHelperContainer {

  @InvokeDynamicHelper
  public static void onStringBuilderInit(
      @Nonnull final StringBuilder builder, @Nullable final CharSequence param) {
    if (!canBeTaintedNullSafe(param)) {
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

  @InvokeDynamicHelper(
      fallbackMethod =
          "java/lang/StringBuilder.append(Ljava/lang/Object;)Ljava/lang/StringBuilder;")
  public static StringBuilder onStringBuilderAppendObject(
      @Nullable final StringBuilder builder, @Nullable final Object param) {
    final String paramStr = String.valueOf(param);
    final StringBuilder result = builder.append(paramStr);
    onStringBuilderAppend(result, paramStr);
    return result;
  }

  @InvokeDynamicHelper
  public static void onStringBuilderAppend(
      @Nonnull final StringBuilder builder, @Nullable final CharSequence param) {
    if (!canBeTainted(builder) || !canBeTaintedNullSafe(param)) {
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

  @InvokeDynamicHelper
  public static void onStringBuilderToString(
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

  private static boolean canBeTaintedNullSafe(@Nullable final CharSequence s) {
    return s != null && canBeTainted(s);
  }

  private static boolean canBeTainted(@Nonnull final CharSequence s) {
    return s.length() > 0;
  }

  private static Range[] getRanges(final TaintedObject taintedObject) {
    return taintedObject == null ? Ranges.EMPTY : taintedObject.getRanges();
  }
}
