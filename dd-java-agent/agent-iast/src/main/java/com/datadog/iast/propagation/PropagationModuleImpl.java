package com.datadog.iast.propagation;

import static com.datadog.iast.taint.Ranges.highestPriorityRange;
import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.taint.Tainteds;
import com.datadog.iast.util.ObjectVisitor;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.lang.ref.SoftReference;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

public class PropagationModuleImpl implements PropagationModule {

  /** Prevent copy of values bigger than this threshold */
  private static final int MAX_VALUE_LENGTH = Config.get().getIastTruncationMaxValueLength();

  @Override
  public void taint(@Nullable final Object target, final byte origin) {
    taint(target, origin, null);
  }

  @Override
  public void taint(
      @Nullable final Object target, final byte origin, @Nullable final CharSequence name) {
    taint(target, origin, name, target);
  }

  @Override
  public void taint(
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (!canBeTainted(target)) {
      return;
    }
    taint(LazyContext.build(), target, origin, name, value);
  }

  @Override
  public void taint(
      @Nullable final IastContext ctx, @Nullable final Object target, final byte origin) {
    taint(ctx, target, origin, null);
  }

  @Override
  public void taint(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name) {
    taint(ctx, target, origin, name, target);
  }

  @Override
  public void taint(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (!canBeTainted(target)) {
      return;
    }
    internalTaint(ctx, target, newSource(origin, name, value), NOT_MARKED);
  }

  @Override
  public void taintIfTainted(@Nullable final Object target, @Nullable final Object input) {
    taintIfTainted(target, input, false, NOT_MARKED);
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target, @Nullable final Object input, boolean keepRanges, int mark) {
    if (!canBeTainted(target) || !canBeTainted(input)) {
      return;
    }
    taintIfTainted(LazyContext.build(), target, input, keepRanges, mark);
  }

  @Override
  public void taintIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input) {
    taintIfTainted(ctx, target, input, false, NOT_MARKED);
  }

  @Override
  public void taintIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      boolean keepRanges,
      int mark) {
    if (!canBeTainted(target) || !canBeTainted(input)) {
      return;
    }
    if (keepRanges) {
      internalTaint(ctx, target, getRanges(ctx, input), mark);
    } else {
      internalTaint(ctx, target, highestPrioritySource(ctx, input), mark);
    }
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target, @Nullable final Object input, final byte origin) {
    taintIfTainted(target, input, origin, null);
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name) {
    taintIfTainted(target, input, origin, name, target);
  }

  @Override
  public void taintIfTainted(
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (!canBeTainted(target) || !canBeTainted(input)) {
      return;
    }
    taintIfTainted(LazyContext.build(), target, input, origin, name, value);
  }

  @Override
  public void taintIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin) {
    taintIfTainted(ctx, target, input, origin, null);
  }

  @Override
  public void taintIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name) {
    taintIfTainted(ctx, target, input, origin, name, target);
  }

  @Override
  public void taintIfTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object input,
      final byte origin,
      @Nullable final CharSequence name,
      @Nullable final Object value) {
    if (!canBeTainted(target) || !canBeTainted(input)) {
      return;
    }
    if (isTainted(ctx, input)) {
      internalTaint(ctx, target, newSource(origin, name, value), NOT_MARKED);
    }
  }

  @Override
  public void taintIfAnyTainted(@Nullable final Object target, @Nullable final Object[] inputs) {
    taintIfAnyTainted(target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintIfAnyTainted(
      @Nullable final Object target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (!canBeTainted(target) || !canBeTainted(inputs)) {
      return;
    }
    taintIfAnyTainted(LazyContext.build(), target, inputs, keepRanges, mark);
  }

  @Override
  public void taintIfAnyTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object[] inputs) {
    taintIfAnyTainted(ctx, target, inputs, false, NOT_MARKED);
  }

  @Override
  public void taintIfAnyTainted(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      @Nullable final Object[] inputs,
      final boolean keepRanges,
      final int mark) {
    if (!canBeTainted(target) || !canBeTainted(inputs)) {
      return;
    }
    if (keepRanges) {
      final Range[] ranges = getRangesInArray(ctx, inputs);
      if (ranges != null) {
        internalTaint(ctx, target, ranges, mark);
      }
    } else {
      final Source source = highestPrioritySourceInArray(ctx, inputs);
      if (source != null) {
        internalTaint(ctx, target, source, mark);
      }
    }
  }

  @Override
  public int taintDeeply(
      @Nullable final Object target, final byte origin, final Predicate<Class<?>> classFilter) {
    if (!canBeTainted(target)) {
      return 0;
    }
    return taintDeeply(LazyContext.build(), target, origin, classFilter);
  }

  @Override
  public int taintDeeply(
      @Nullable final IastContext ctx,
      @Nullable final Object target,
      final byte origin,
      final Predicate<Class<?>> classFilter) {
    if (!canBeTainted(target)) {
      return 0;
    }
    final TaintedObjects to = getTaintedObjects(ctx);
    if (to == null) {
      return 0;
    }
    if (target instanceof CharSequence) {
      internalTaint(ctx, target, newSource(origin, null, target), NOT_MARKED);
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
    return target == null ? null : findSource(LazyContext.build(), target);
  }

  @Nullable
  @Override
  public Taintable.Source findSource(
      @Nullable final IastContext ctx, @Nullable final Object target) {
    if (target == null) {
      return null;
    }
    return highestPrioritySource(ctx, target);
  }

  @Override
  public boolean isTainted(@Nullable final Object target) {
    return target != null && isTainted(LazyContext.build(), target);
  }

  @Override
  public boolean isTainted(@Nullable final IastContext ctx, @Nullable final Object target) {
    return target != null && findSource(ctx, target) != null;
  }

  /**
   * Ensures that the reference is not kept due to a strong reference via the name or value
   * properties
   */
  private static Source newSource(
      final byte origin, @Nullable final CharSequence name, @Nullable final Object value) {
    return new Source(origin, sourceString(name), sourceString(value));
  }

  /**
   * This method will prevent the code from creating a strong reference to what should remain soft
   * reachable
   */
  @Nullable
  private static Object sourceString(@Nullable final Object target) {
    if (target instanceof String) {
      return new SoftReference<>(target);
    } else if (target instanceof CharSequence) {
      // char-sequences can mutate so we have to keep a snapshot
      CharSequence charSequence = (CharSequence) target;
      if (charSequence.length() > MAX_VALUE_LENGTH) {
        charSequence = charSequence.subSequence(0, MAX_VALUE_LENGTH);
      }
      return charSequence.toString();
    }
    return null; // ignore non char-sequence instances (e.g. byte buffers)
  }

  @Contract("null -> false")
  private static boolean canBeTainted(@Nullable final Object target) {
    if (target == null) {
      return false;
    }
    if (target instanceof CharSequence) {
      return Tainteds.canBeTainted((CharSequence) target);
    }
    return true;
  }

  @Contract("null -> false")
  private static boolean canBeTainted(@Nullable final Object[] target) {
    if (target == null || target.length == 0) {
      return false;
    }
    return true;
  }

  @Nullable
  private static TaintedObjects getTaintedObjects(@Nullable final IastContext ctx) {
    return ctx == null ? null : ctx.getTaintedObjects();
  }

  @Nullable
  private static Range[] getRangesInArray(
      final @Nullable IastContext ctx, final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Range[] ranges = getRanges(ctx, object);
      if (ranges != null) {
        return ranges;
      }
    }
    return null;
  }

  @Nullable
  private static Range[] getRanges(final @Nullable IastContext ctx, final @Nonnull Object object) {
    if (object instanceof Taintable) {
      final Source source = highestPrioritySource(ctx, object);
      if (source == null) {
        return null;
      } else {
        return new Range[] {new Range(0, Integer.MAX_VALUE, source, NOT_MARKED)};
      }
    }
    final TaintedObjects to = getTaintedObjects(ctx);
    if (to == null) {
      return null;
    }
    final TaintedObject tainted = to.get(object);
    return tainted == null ? null : tainted.getRanges();
  }

  @Nullable
  private static Source highestPrioritySourceInArray(
      final @Nullable IastContext ctx, final @Nonnull Object[] objects) {
    for (final Object object : objects) {
      final Source source = highestPrioritySource(ctx, object);
      if (source != null) {
        return source;
      }
    }
    return null;
  }

  @Nullable
  private static Source highestPrioritySource(
      final @Nullable IastContext ctx, final @Nonnull Object object) {
    if (object instanceof Taintable) {
      return (Source) ((Taintable) object).$$DD$getSource();
    } else {
      final Range[] ranges = getRanges(ctx, object);
      return ranges != null && ranges.length > 0 ? highestPriorityRange(ranges).getSource() : null;
    }
  }

  private static void internalTaint(
      @Nullable final IastContext ctx,
      @Nonnull final Object value,
      @Nullable final Source source,
      int mark) {
    if (source == null) {
      return;
    }
    if (value instanceof Taintable) {
      ((Taintable) value).$$DD$setSource(source);
    } else {
      final TaintedObjects to = getTaintedObjects(ctx);
      if (to == null) {
        return;
      }
      if (value instanceof CharSequence) {
        to.taint(value, Ranges.forCharSequence((CharSequence) value, source, mark));
      } else {
        to.taint(value, Ranges.forObject(source, mark));
      }
    }
  }

  private static void internalTaint(
      @Nullable final IastContext ctx,
      @Nonnull final Object value,
      @Nullable final Range[] ranges,
      final int mark) {
    if (ranges == null || ranges.length == 0) {
      return;
    }
    if (value instanceof Taintable) {
      ((Taintable) value).$$DD$setSource(ranges[0].getSource());
    } else {
      final TaintedObjects to = getTaintedObjects(ctx);
      if (to != null) {
        final Range[] markedRanges = markRanges(ranges, mark);
        to.taint(value, markedRanges);
      }
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

  private static class LazyContext implements IastContext {

    private boolean fetched;
    @Nullable private IastContext delegate;

    @Nullable
    private IastContext getDelegate() {
      if (!fetched) {
        fetched = true;
        delegate = IastContext.Provider.get();
      }
      return delegate;
    }

    public static IastContext build() {
      return new LazyContext();
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    @Override
    public TaintedObjects getTaintedObjects() {
      final IastContext delegate = getDelegate();
      return delegate == null ? TaintedObjects.NoOp.INSTANCE : delegate.getTaintedObjects();
    }
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
        if (canBeTainted(charSequence)) {
          final Source source = newSource(origin, path, charSequence);
          count++;
          taintedObjects.taint(
              charSequence, Ranges.forCharSequence(charSequence, source, NOT_MARKED));
        }
      }
      return CONTINUE;
    }

    public int getCount() {
      return count;
    }
  }
}
