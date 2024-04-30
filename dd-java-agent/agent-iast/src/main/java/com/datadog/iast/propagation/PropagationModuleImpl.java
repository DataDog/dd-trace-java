package com.datadog.iast.propagation;

import static com.datadog.iast.model.Source.PROPAGATION_PLACEHOLDER;
import static com.datadog.iast.taint.Ranges.highestPriorityRange;
import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.ObjectVisitor;
import com.datadog.iast.util.Ranged;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.lang.ref.WeakReference;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("DuplicatedCode")
public class PropagationModuleImpl implements PropagationModule {

  /** Prevent copy of values bigger than this threshold */
  private static final int MAX_VALUE_LENGTH = Config.get().getIastTruncationMaxValueLength();

  @Override
  public void taintObject(@Nullable Object target, byte origin) {
    taintObject(target, origin, null);
  }

  @Override
  public void taintObject(@Nullable IastContext ctx, @Nullable Object target, byte origin) {
    taintObject(ctx, target, origin, null);
  }

  @Override
  public void taintString(@Nullable String target, byte origin) {
    taintString(target, origin, null);
  }

  @Override
  public void taintString(@Nullable IastContext ctx, @Nullable String target, byte origin) {
    taintString(ctx, target, origin, null);
  }

  @Override
  public void taintObject(@Nullable Object target, byte origin, @Nullable CharSequence name) {
    taintObject(target, origin, name, target);
  }

  @Override
  public void taintObject(
      @Nullable IastContext ctx,
      @Nullable Object target,
      byte origin,
      @Nullable CharSequence name) {
    taintObject(ctx, target, origin, name, target);
  }

  @Override
  public void taintString(@Nullable String target, byte origin, @Nullable CharSequence name) {
    taintString(target, origin, name, target);
  }

  @Override
  public void taintString(
      @Nullable IastContext ctx,
      @Nullable String target,
      byte origin,
      @Nullable CharSequence name) {
    taintString(ctx, target, origin, name, target);
  }

  @Override
  public void taintObjectRange(
      @Nullable final Object target, final byte origin, final int start, final int length) {
    if (target == null || length == 0) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintObjectRange(ctx, target, origin, start, length);
  }

  @Override
  public void taintObjectRange(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      final byte origin,
      final int start,
      final int length) {
    if (ctx == null || target == null || length == 0) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final Range range =
        new Range(start, length, newSource(target, origin, null, target), NOT_MARKED);
    internalTaint(to, target, new Range[] {range}, NOT_MARKED);
  }

  @Override
  public void taintStringRange(
      @Nullable final String target, final byte origin, final int start, final int length) {
    if (target == null || length == 0) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintStringRange(ctx, target, origin, start, length);
  }

  @Override
  public void taintStringRange(
      @Nullable final IastContext ctx,
      @Nullable final String target,
      final byte origin,
      final int start,
      final int length) {
    if (ctx == null || target == null || length == 0) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final Range range =
        new Range(start, length, newSource(target, origin, null, target), NOT_MARKED);
    internalTaint(to, target, new Range[] {range}, NOT_MARKED);
  }

  @Override
  public void taintObject(
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (target == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintObject(ctx, target, origin, name, value);
  }

  @Override
  public void taintObject(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (ctx == null || target == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    internalTaint(to, target, newSource(target, origin, name, value), NOT_MARKED);
  }

  @Override
  public void taintString(
      @Nullable final String target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final CharSequence value) {
    if (target == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintString(ctx, target, origin, name, value);
  }

  @Override
  public void taintString(
      @Nullable final IastContext ctx,
      @Nullable final String target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final CharSequence value) {
    if (ctx == null || target == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    internalTaint(to, target, newSource(target, origin, name, value), NOT_MARKED);
  }

  @Override
  public void taintObjectIfTainted(@Nullable Object target, @Nullable Object input) {
    taintObjectIfTainted(target, input, false, NOT_MARKED);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object input) {
    taintObjectIfTainted(ctx, target, input, false, NOT_MARKED);
  }

  @Override
  public void taintStringIfTainted(@Nullable String target, @Nullable Object input) {
    taintStringIfTainted(target, input, false, NOT_MARKED);
  }

  @Override
  public void taintStringIfTainted(
      @Nullable IastContext ctx, @Nullable String target, @Nullable Object input) {
    taintStringIfTainted(ctx, target, input, false, NOT_MARKED);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final Object target, @Nullable final Object input, boolean keepRanges, int mark) {
    if (target == null || input == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintObjectIfTainted(ctx, target, input, keepRanges, mark);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      boolean keepRanges,
      int mark) {
    if (ctx == null || target == null || input == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    if (keepRanges) {
      internalTaint(to, target, getRanges(to, input), mark);
    } else {
      internalTaint(to, target, highestPrioritySource(to, input), mark);
    }
  }

  @Override
  public void taintStringIfTainted(
      @Nullable final String target, @Nullable final Object input, boolean keepRanges, int mark) {
    if (target == null || input == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintStringIfTainted(ctx, target, input, keepRanges, mark);
  }

  @Override
  public void taintStringIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final String target,
      @Nullable final Object input,
      boolean keepRanges,
      int mark) {
    if (ctx == null || target == null || input == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    if (keepRanges) {
      internalTaint(to, target, getRanges(to, input), mark);
    } else {
      internalTaint(to, target, highestPrioritySource(to, input), mark);
    }
  }

  @Override
  public void taintObjectIfRangeTainted(
      @Nullable final Object target,
      @Nullable final Object input,
      final int start,
      final int length,
      boolean keepRanges,
      int mark) {
    if (target == null || input == null || length == 0) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintObjectIfRangeTainted(ctx, target, input, start, length, keepRanges, mark);
  }

  @Override
  public void taintObjectIfRangeTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      final int start,
      final int length,
      boolean keepRanges,
      int mark) {
    if (ctx == null || target == null || input == null || length == 0) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final Range[] ranges = getRanges(to, input);
    if (ranges == null || ranges.length == 0) {
      return;
    }
    final Range[] intersection = Ranges.intersection(Ranged.build(start, length), ranges);
    if (intersection == null || intersection.length == 0) {
      return;
    }
    if (keepRanges) {
      internalTaint(to, target, intersection, mark);
    } else {
      final Range range = highestPriorityRange(intersection);
      internalTaint(to, target, range.getSource(), mark);
    }
  }

  @Override
  public void taintStringIfRangeTainted(
      @Nullable final String target,
      @Nullable final Object input,
      final int start,
      final int length,
      boolean keepRanges,
      int mark) {
    if (target == null || input == null || length == 0) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintStringIfRangeTainted(ctx, target, input, start, length, keepRanges, mark);
  }

  @Override
  public void taintStringIfRangeTainted(
      @Nullable final IastContext ctx,
      @Nullable final String target,
      @Nullable final Object input,
      final int start,
      final int length,
      boolean keepRanges,
      int mark) {
    if (ctx == null || target == null || input == null || length == 0) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final Range[] ranges = getRanges(to, input);
    if (ranges == null || ranges.length == 0) {
      return;
    }
    final Range[] intersection = Ranges.intersection(Ranged.build(start, length), ranges);
    if (intersection == null || intersection.length == 0) {
      return;
    }
    if (keepRanges) {
      internalTaint(to, target, intersection, mark);
    } else {
      final Range range = highestPriorityRange(intersection);
      internalTaint(to, target, range.getSource(), mark);
    }
  }

  @Override
  public void taintObjectIfTainted(@Nullable Object target, @Nullable Object input, byte origin) {
    taintObjectIfTainted(target, input, origin, null, target);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object input, byte origin) {
    taintObjectIfTainted(ctx, target, input, origin, null, target);
  }

  @Override
  public void taintStringIfTainted(@Nullable String target, @Nullable Object input, byte origin) {
    taintStringIfTainted(target, input, origin, null, target);
  }

  @Override
  public void taintStringIfTainted(
      @Nullable IastContext ctx, @Nullable String target, @Nullable Object input, byte origin) {
    taintStringIfTainted(ctx, target, input, origin, null, target);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable Object target, @Nullable Object input, byte origin, @Nullable CharSequence name) {
    taintObjectIfTainted(target, input, origin, name, target);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable IastContext ctx,
      @Nullable Object target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name) {
    taintObjectIfTainted(ctx, target, input, origin, name, target);
  }

  @Override
  public void taintStringIfTainted(
      @Nullable String target, @Nullable Object input, byte origin, @Nullable CharSequence name) {
    taintStringIfTainted(target, input, origin, name, target);
  }

  @Override
  public void taintStringIfTainted(
      @Nullable IastContext ctx,
      @Nullable String target,
      @Nullable Object input,
      byte origin,
      @Nullable CharSequence name) {
    taintStringIfTainted(ctx, target, input, origin, name, target);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (target == null || input == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintObjectIfTainted(ctx, target, input, origin, name, value);
  }

  @Override
  public void taintObjectIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (ctx == null || target == null || input == null) {
      return;
    }
    if (isTainted(ctx, target)) {
      final TaintedObjects to = ctx.getTaintedObjects();
      internalTaint(to, target, newSource(target, origin, name, value), NOT_MARKED);
    }
  }

  @Override
  public void taintStringIfTainted(
      @Nullable final String target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (target == null || input == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintStringIfTainted(ctx, target, input, origin, name, value);
  }

  @Override
  public void taintStringIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final String target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (ctx == null || target == null || input == null) {
      return;
    }
    if (isTainted(ctx, input)) {
      final TaintedObjects to = ctx.getTaintedObjects();
      internalTaint(to, target, newSource(target, origin, name, value), NOT_MARKED);
    }
  }

  @Override
  public void taintObjectIfAnyTainted(@Nullable Object target, @Nullable Object[] inputs) {
    taintObjectIfAnyTainted(target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintObjectIfAnyTainted(
      @Nullable IastContext ctx, @Nullable Object target, @Nullable Object[] inputs) {
    taintObjectIfAnyTainted(ctx, target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintStringIfAnyTainted(@Nullable String target, @Nullable Object[] inputs) {
    taintStringIfAnyTainted(target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintStringIfAnyTainted(
      @Nullable IastContext ctx, @Nullable String target, @Nullable Object[] inputs) {
    taintStringIfAnyTainted(ctx, target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintObjectIfAnyTainted(
      @Nullable final Object target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (target == null || inputs == null || inputs.length == 0) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintObjectIfAnyTainted(ctx, target, inputs, keepRanges, mark);
  }

  @Override
  public void taintObjectIfAnyTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (ctx == null || target == null || inputs == null || inputs.length == 0) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    if (keepRanges) {
      final Range[] ranges = getRangesInArray(to, inputs);
      if (ranges != null) {
        internalTaint(to, target, ranges, mark);
      }
    } else {
      final Source source = highestPrioritySourceInArray(to, inputs);
      if (source != null) {
        internalTaint(to, target, source, mark);
      }
    }
  }

  @Override
  public void taintStringIfAnyTainted(
      @Nullable final String target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (target == null || inputs == null || inputs.length == 0) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    taintStringIfAnyTainted(ctx, target, inputs, keepRanges, mark);
  }

  @Override
  public void taintStringIfAnyTainted(
      @Nullable final IastContext ctx,
      @Nullable final String target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (ctx == null || target == null || inputs == null || inputs.length == 0) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    if (keepRanges) {
      final Range[] ranges = getRangesInArray(to, inputs);
      if (ranges != null) {
        internalTaint(to, target, ranges, mark);
      }
    } else {
      final Source source = highestPrioritySourceInArray(to, inputs);
      if (source != null) {
        internalTaint(to, target, source, mark);
      }
    }
  }

  @Override
  public int taintObjectDeeply(
      @Nullable final Object target, final byte origin, final Predicate<Class<?>> classFilter) {
    if (target == null) {
      return 0;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return 0;
    }
    return taintObjectDeeply(ctx, target, origin, classFilter);
  }

  @Override
  public int taintObjectDeeply(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      final byte origin,
      final Predicate<Class<?>> classFilter) {
    if (ctx == null || target == null) {
      return 0;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    if (target instanceof CharSequence) {
      internalTaint(to, target, newSource(target, origin, null, target), NOT_MARKED);
      return 1;
    } else {
      final TaintingVisitor visitor = new TaintingVisitor(to, origin);
      ObjectVisitor.visit(target, visitor, classFilter);
      return visitor.getCount();
    }
  }

  @Nullable
  @Override
  public Taintable.Source findSource(@Nullable final Object target) {
    if (target == null) {
      return null;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return null;
    }
    return findSource(ctx, target);
  }

  @Nullable
  @Override
  public Taintable.Source findSource(
      @Nullable final IastContext ctx, @Nullable final Object target) {
    if (ctx == null || target == null) {
      return null;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    return highestPrioritySource(to, target);
  }

  @Override
  public boolean isTainted(@Nullable final Object target) {
    if (target == null) {
      return false;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return false;
    }
    return isTainted(ctx, target);
  }

  @Override
  public boolean isTainted(@Nullable final IastContext ctx, @Nullable final Object target) {
    return ctx != null && target != null && findSource(ctx, target) != null;
  }

  /**
   * Ensures that the reference is not kept due to a strong reference via the name or value
   * properties
   */
  private static Source newSource(
      @Nonnull final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    final Object sourceValue = sourceReference(target, value, true);
    final Object sourceName = name == value ? sourceValue : sourceReference(target, name, false);
    return new Source(origin, sourceName, sourceValue);
  }

  /**
   * Ensures that the reference is not kept due to a strong reference via the name or value
   * properties
   */
  private static Source newSource(
      @Nonnull final String tainted,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final CharSequence value) {
    final Object sourceValue = sourceReference(tainted, value, true);
    final Object sourceName = name == value ? sourceValue : sourceReference(tainted, name, false);
    return new Source(origin, sourceName, sourceValue);
  }

  /**
   * This method will prevent the code from creating a strong reference to what should remain weakly
   * reachable
   */
  @Nullable
  private static Object sourceReference(
      @Nonnull final Object tainted, @Nullable final Object target, final boolean value) {
    if (target instanceof String) {
      // weak reference if it's a value or matches the tainted value
      return value || tainted == target ? new WeakReference<>(target) : target;
    } else if (target instanceof CharSequence) {
      // char-sequences can mutate so we have to keep a snapshot
      CharSequence charSequence = (CharSequence) target;
      if (charSequence.length() > MAX_VALUE_LENGTH) {
        charSequence = charSequence.subSequence(0, MAX_VALUE_LENGTH);
      }
      return charSequence.toString();
    }
    // ignore non char-sequence instances (e.g. byte buffers)
    return value ? PROPAGATION_PLACEHOLDER : null;
  }

  @Nullable
  private static Range[] getRangesInArray(
      final @Nonnull TaintedObjects to, final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Range[] ranges = getRanges(to, object);
      if (ranges != null) {
        return ranges;
      }
    }
    return null;
  }

  @Nullable
  private static Range[] getRanges(final @Nonnull TaintedObjects to, final @Nonnull Object object) {
    if (object instanceof Taintable) {
      final Source source = highestPrioritySource(to, object);
      if (source == null) {
        return null;
      } else {
        return new Range[] {new Range(0, Integer.MAX_VALUE, source, NOT_MARKED)};
      }
    }
    final TaintedObject tainted = to.get(object);
    return tainted == null ? null : tainted.getRanges();
  }

  @Nullable
  private static Source highestPrioritySourceInArray(
      final @Nonnull TaintedObjects to, final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Source source = highestPrioritySource(to, object);
      if (source != null) {
        return source;
      }
    }
    return null;
  }

  @Nullable
  private static Source highestPrioritySource(
      final @Nonnull TaintedObjects to, final @Nonnull Object object) {
    if (object instanceof Taintable) {
      return (Source) ((Taintable) object).$$DD$getSource();
    } else {
      final Range[] ranges = getRanges(to, object);
      return ranges != null && ranges.length > 0 ? highestPriorityRange(ranges).getSource() : null;
    }
  }

  private static void internalTaint(
      @Nonnull final TaintedObjects to,
      @Nonnull final Object value,
      @Nullable Source source,
      int mark) {
    if (source == null) {
      return;
    }
    if (value instanceof Taintable) {
      ((Taintable) value).$$DD$setSource(source);
    } else {
      if (value instanceof CharSequence) {
        source = source.attachValue((CharSequence) value);
        to.taint(value, Ranges.forCharSequence((CharSequence) value, source, mark));
      } else {
        to.taint(value, Ranges.forObject(source, mark));
      }
    }
  }

  private static void internalTaint(
      @Nonnull final TaintedObjects to,
      @Nonnull final String value,
      @Nullable Source source,
      int mark) {
    if (source == null) {
      return;
    }
    source = source.attachValue(value);
    to.taint(value, Ranges.forCharSequence(value, source, mark));
  }

  private static void internalTaint(
      @Nonnull final TaintedObjects to,
      @Nonnull final Object value,
      @Nullable Range[] ranges,
      final int mark) {
    if (ranges == null || ranges.length == 0) {
      return;
    }
    if (value instanceof Taintable) {
      final Taintable taintable = (Taintable) value;
      if (!taintable.$DD$isTainted()) {
        taintable.$$DD$setSource(ranges[0].getSource());
      }
    } else {
      if (value instanceof CharSequence) {
        ranges = attachSourceValue(ranges, (CharSequence) value);
      }
      ranges = markRanges(ranges, mark);
      final TaintedObject tainted = to.get(value);
      if (tainted != null) {
        // append ranges
        final Range[] newRanges = Ranges.mergeRangesSorted(tainted.getRanges(), ranges);
        tainted.setRanges(newRanges);
      } else {
        // taint new value
        to.taint(value, ranges);
      }
    }
  }

  private static void internalTaint(
      @Nonnull final TaintedObjects to,
      @Nonnull final String value,
      @Nullable Range[] ranges,
      final int mark) {
    if (ranges == null || ranges.length == 0) {
      return;
    }
    ranges = attachSourceValue(ranges, value);
    ranges = markRanges(ranges, mark);
    final TaintedObject tainted = to.get(value);
    if (tainted != null) {
      // append ranges
      final Range[] newRanges = Ranges.mergeRangesSorted(tainted.getRanges(), ranges);
      tainted.setRanges(newRanges);
    } else {
      // taint new value
      to.taint(value, ranges);
    }
  }

  @Nonnull
  private static Range[] markRanges(@Nonnull final Range[] ranges, final int mark) {
    if (mark == NOT_MARKED) {
      return ranges;
    }
    final Range[] result = new Range[ranges.length];
    for (int i = 0; i < ranges.length; i++) {
      final Range range = ranges[i];
      final int newMark = range.getMarks() | mark;
      result[i] = new Range(range.getStart(), range.getLength(), range.getSource(), newMark);
    }
    return result;
  }

  private static Range[] attachSourceValue(
      @Nonnull final Range[] ranges, @Nonnull final CharSequence value) {
    // unbound sources can only occur when there's a single range in the array
    if (ranges.length != 1) {
      return ranges;
    }
    final Range range = ranges[0];
    final Source source = range.getSource();
    final Source newSource = range.getSource().attachValue(value);
    return newSource == source
        ? ranges
        : Ranges.forCharSequence(value, newSource, range.getMarks());
  }

  private static class TaintingVisitor implements ObjectVisitor.Visitor {

    private final TaintedObjects taintedObjects;
    private final byte origin;
    private int count;

    private TaintingVisitor(@Nonnull final TaintedObjects taintedObjects, final byte origin) {
      this.taintedObjects = taintedObjects;
      this.origin = origin;
    }

    @Nonnull
    @Override
    public ObjectVisitor.State visit(@Nonnull final String path, @Nonnull final Object value) {
      if (value instanceof CharSequence) {
        final CharSequence charSequence = (CharSequence) value;
        final Source source = newSource(charSequence, origin, path, charSequence);
        count++;
        taintedObjects.taint(
            charSequence, Ranges.forCharSequence(charSequence, source, NOT_MARKED));
      }
      return CONTINUE;
    }

    public int getCount() {
      return count;
    }
  }
}
